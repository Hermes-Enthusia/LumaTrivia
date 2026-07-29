package net.badgersmc.trivia.application

import net.badgersmc.nexus.scheduler.NexusScheduler
import net.badgersmc.trivia.domain.PlayerStats
import net.badgersmc.trivia.domain.Question
import net.badgersmc.trivia.infrastructure.config.TriviaConfig
import net.badgersmc.trivia.infrastructure.persistence.StatsRepository
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Core trivia game lifecycle service (REQ-001..007, REQ-012, REQ-015).
 * Manages game state, answer validation, muting, cooldowns, rewards, and scheduled games.
 */
class TriviaService(
    private val plugin: JavaPlugin,
    private var config: TriviaConfig,
    var fetcher: QuestionFetcher,
    private val statsRepo: StatsRepository,
    private val scheduler: NexusScheduler,
    private val chat: ChatPlatform,
) {
    enum class AnswerResult { CORRECT, WRONG, ALREADY_ANSWERED, NO_GAME }

    /** Callback for broadcasting MiniMessage to all players. */
    var broadcast: ((message: Component) -> Unit)? = null
    /** Callback for fetching questions asynchronously (called when cache is empty). */
    var fetchCallback: (() -> Unit)? = null

    /** Guard against concurrent fetch-then-start cycles. */
    private var fetching: Boolean = false

    private var gameActive: Boolean = false
    private var gameTaskId: Int = -1
    private var cooldownUntil: Long = 0L
    private var currentQuestionData: Question? = null
    private val answeredPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    val currentQuestion: Question? get() = currentQuestionData
    val isActive: Boolean get() = gameActive

    fun isGameActive(): Boolean = gameActive

    /** Check if player already answered this round. Thread-safe early-out for the listener. */
    fun hasPlayerAnswered(uuid: UUID): Boolean = answeredPlayers.contains(uuid)

    /** Update config at runtime (for reload). */
    fun updateConfig(newConfig: TriviaConfig) {
        config = newConfig
    }

    /** Called by the plugin after async fetch completes, regardless of outcome. */
    fun onFetchDone() {
        fetching = false
    }

    /** Start initial async cache fill via guarded path (prevents concurrent fetches). */
    fun startPrewarm() {
        if (!fetching) {
            fetching = true
            fetchCallback?.invoke()
        }
    }

    /** Start a trivia game. Returns true if the game was started. */
    fun startGame(): Boolean {
        if (gameActive) return false
        if (System.currentTimeMillis() < cooldownUntil) return false

        if (fetcher.isEmpty) {
            if (!fetching) {
                fetching = true
                fetchCallback?.invoke()
            }
            return false
        }

        val question = fetcher.poll() ?: return false
        currentQuestionData = question
        answeredPlayers.clear()
        gameActive = true

        // Broadcast game start + question (escaped) + options
        val mm = MiniMessage.miniMessage()
        broadcast?.invoke(mm.deserialize("<yellow>A new trivia question has been asked!</yellow>"))
        broadcast?.invoke(mm.deserialize("<aqua>${mm.escapeTags(question.question)}</aqua>"))
        broadcast?.invoke(mm.deserialize("<yellow><bold>Options:</bold></yellow>\n${question.formattedAnswers}"))

        // Schedule time-up task
        val answerTime = config.game.answerTime
        gameTaskId = plugin.server.scheduler.scheduleSyncDelayedTask(plugin, { timeUp() }, answerTime * 20L)

        return true
    }

    /** Process a player's answer. Must be called on the main thread. */
    fun checkAnswer(player: Player, answer: String): AnswerResult {
        if (!gameActive) return AnswerResult.NO_GAME
        val question = currentQuestionData ?: return AnswerResult.NO_GAME

        if (answeredPlayers.contains(player.uniqueId)) {
            return AnswerResult.ALREADY_ANSWERED
        }
        answeredPlayers.add(player.uniqueId)

        if (question.isCorrectAnswer(answer)) {
            endGame()
            recordWin(player, question)
            giveRewards(player, question.difficulty)
            return AnswerResult.CORRECT
        }

        // Wrong answer — delegate mute to chat platform
        if (config.game.muteIncorrect.enabled && !player.hasPermission("lumatrivia.mute.bypass")) {
            chat.mutePlayer(player, config.game.answerTime)
        }
        return AnswerResult.WRONG
    }

    /** Called when the answer timer expires. */
    fun timeUp() {
        if (!gameActive) return
        val question = currentQuestionData
        endGame()
        if (question != null) {
            val mm = MiniMessage.miniMessage()
            broadcast?.invoke(mm.deserialize(
                "<red>Time's up!</red> <gray>The correct answer was:</gray> <gold>${mm.escapeTags(question.correctAnswer)}</gold> <dark_gray>(${question.correctAnswerLetter})</dark_gray>"
            ))
        }
    }

    /** Get the cooldown remaining in seconds, or 0 if no cooldown. */
    fun cooldownRemaining(): Long {
        val remaining = (cooldownUntil - System.currentTimeMillis()) / 1000
        return if (remaining > 0) remaining else 0
    }

    private fun endGame() {
        gameActive = false
        if (gameTaskId != -1) {
            plugin.server.scheduler.cancelTask(gameTaskId)
            gameTaskId = -1
        }
        // Release all mutes — round is over
        chat.clearMutes()
        // Cooldown starts when game ends
        cooldownUntil = System.currentTimeMillis() + (config.game.cooldown * 1000L)
    }

    private fun recordWin(player: Player, question: Question) {
        val playerId = player.uniqueId
        val existing = statsRepo.findByPlayerId(playerId)
        val stats = existing ?: PlayerStats(playerId, player.name)
        stats.playerName = player.name
        val diff = question.difficulty.lowercase()
        val pts = config.rewards[diff]?.points ?: when (diff) { "hard" -> 3; "medium" -> 2; else -> 1 }
        stats.addCorrectAnswer(
            difficulty = question.difficulty,
            easyPoints = if (diff == "easy") pts else (config.rewards["easy"]?.points ?: 1),
            mediumPoints = if (diff == "medium") pts else (config.rewards["medium"]?.points ?: 2),
            hardPoints = if (diff == "hard") pts else (config.rewards["hard"]?.points ?: 3),
        )
        statsRepo.save(stats)
    }

    private fun giveRewards(player: Player, difficulty: String) {
        val rewards = config.rewards[difficulty.lowercase()] ?: return
        plugin.server.scheduler.runTask(plugin, Runnable {
            for (command in rewards.commands) {
                plugin.server.dispatchCommand(
                    plugin.server.consoleSender,
                    command.replace("%player%", player.name)
                )
            }
        })
    }
}
