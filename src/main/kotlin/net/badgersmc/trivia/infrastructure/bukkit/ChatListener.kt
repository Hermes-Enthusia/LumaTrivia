package net.badgersmc.trivia.infrastructure.bukkit

import io.papermc.paper.event.player.AsyncChatEvent
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.trivia.application.ChatPlatform
import net.badgersmc.trivia.application.TriviaService
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * Single listener for mute enforcement and answer parsing (REQ-019).
 * Delegates mute control and channel validation to [ChatPlatform].
 *
 * On vanilla Paper: cancels AsyncChatEvent at LOWEST priority.
 * On RoseChat: mute only enforced for the trivia channel; other channels are untouched.
 */
class ChatListener(
    private val triviaService: TriviaService,
    private val lang: LangService,
    private val chatPlatform: ChatPlatform,
) : Listener {

    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player

        // Only active during a game
        if (!triviaService.isActive) return

        // Extract plain text from any Component type (TextComponent, TranslatableComponent, etc.)
        val content = plain.serialize(event.message()).trim()
        if (content.isEmpty()) return

        // Channel/platform check first — only trivia-channel messages proceed
        if (!chatPlatform.isAnswerChat(player, content)) return
        val answer = chatPlatform.extractAnswer(content) ?: return

        // Mute enforcement — only blocks trivia-channel answers
        if (chatPlatform.isMuted(player) && !player.hasPermission("lumatrivia.mute.bypass")) {
            event.isCancelled = true
            player.sendMessage(lang.msg("mute.muted"))
            return
        }

        // Valid answer formats: single letter or t/f/true/false
        val normalized = answer.trim().lowercase()
        val question = triviaService.currentQuestion ?: return
        val maxLetter = 'a' + (question.answerCount - 1)
        val isValidAnswer = when {
            normalized.length == 1 && normalized[0] in 'a'..maxLetter -> true
            normalized.matches(Regex("^(t(rue)?|f(alse)?)$")) -> true
            else -> false
        }

        if (isValidAnswer) {
            event.isCancelled = true
            val mapped = when {
                normalized.startsWith("t") -> "true"
                normalized.startsWith("f") -> "false"
                else -> normalized
            }
            player.server.scheduler.runTask(
                player.server.pluginManager.getPlugin("LumaTrivia")!!,
                Runnable {
                    if (!player.isOnline) return@Runnable
                    val result = triviaService.checkAnswer(player, mapped)
                    when (result) {
                        TriviaService.AnswerResult.CORRECT -> {
                            val q = triviaService.currentQuestion ?: return@Runnable
                            player.server.broadcast(
                                lang.msg(
                                    "game.correct_answer",
                                    "player" to player.name,
                                    "answer" to q.correctAnswer,
                                    "letter" to q.correctAnswerLetter,
                                )
                            )
                        }
                        TriviaService.AnswerResult.WRONG -> {
                            player.server.broadcast(
                                lang.msg(
                                    "game.wrong_answer",
                                    "player" to player.name,
                                    "answer" to content,
                                )
                            )
                            if (chatPlatform.isMuted(player)) {
                                player.sendMessage(lang.msg("mute.muted"))
                            }
                        }
                        TriviaService.AnswerResult.ALREADY_ANSWERED -> {
                            player.sendMessage(lang.msg("game.already_answered"))
                        }
                        TriviaService.AnswerResult.NO_GAME -> {}
                    }
                }
            )
        }
    }
}
