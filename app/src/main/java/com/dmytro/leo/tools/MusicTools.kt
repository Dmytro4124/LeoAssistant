package com.dmytro.leo.tools

import com.dmytro.leo.data.InstalledApps
import com.dmytro.leo.data.MusicItem
import com.dmytro.leo.data.MusicSearch
import com.dmytro.leo.data.MusicType
import com.dmytro.leo.data.PlaylistStore
import com.dmytro.leo.data.PreferredApps
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** YouTube Music links that start playing right away. */
object MusicLinks {
    fun track(videoId: String) = "https://music.youtube.com/watch?v=$videoId"
    fun list(playlistId: String) = "https://music.youtube.com/watch?list=$playlistId"
}

class PlayMusicTool(
    private val search: MusicSearch,
    private val playlists: PlaylistStore,
    private val apps: InstalledApps,
    private val phone: PhoneActions,
) : Tool {

    override val name = "play_music"
    override val description =
        "Увімкнути музику в YouTube Music: пісню, виконавця (його мікс), альбом або плейлист. " +
            "Для збережених плейлистів користувача використовуй їхню назву і type=playlist."
    override val parameters = objectSchema(
        Param("query", "Що шукати: «Океан Ельзи Обійми», «Imagine Dragons», назва альбому чи плейлиста"),
        Param(
            "type",
            "Що вмикати: song — конкретна пісня (є назва пісні); artist — названо лише виконавця чи гурт " +
                "(«увімкни Океан Ельзи» → artist); album — альбом; playlist — плейлист",
            enum = listOf("song", "artist", "album", "playlist"),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.string("query") ?: return ToolResult.Failure("Не вказано, що вмикати")
        val type = when (args.string("type")) {
            "artist" -> MusicType.Artist
            "album" -> MusicType.Album
            "playlist" -> MusicType.Playlist
            else -> MusicType.Song
        }

        // The user's own playlist by name comes first: search can't see private playlists
        playlists.find(query)?.let { id -> return play(MusicLinks.list(id), "плейлист «$query»") }

        val results = try {
            search.search(query, type)
        } catch (e: IOException) {
            return ToolResult.Failure("Не вдалося виконати пошук у YouTube Music: немає зв'язку")
        }
        val item = results.firstOrNull { it.linkFor(type) != null }
            ?: return ToolResult.Failure("У YouTube Music нічого не знайдено за запитом «$query»")
        val what = listOf(item.title, item.subtitle).filter { it.isNotEmpty() }.joinToString(" — ")
        // Not one of the user's playlists: say so, or they would think it is theirs
        val note = if (type == MusicType.Playlist) {
            "Збереженого плейлиста «$query» немає (його можна зберегти: «Поділитися» → Leo), тому вмикаю знайдений публічний: "
        } else ""
        return play(item.linkFor(type)!!, note + what)
    }

    private fun MusicItem.linkFor(type: MusicType): String? = when (type) {
        MusicType.Song -> videoId?.let(MusicLinks::track) ?: playlistId?.let(MusicLinks::list)
        else -> playlistId?.let(MusicLinks::list) ?: videoId?.let(MusicLinks::track)
    }

    private fun play(link: String, what: String): ToolResult {
        val player = PreferredApps.youTubeMusic(apps.list().map { it.packageName }.toSet())
        val opened = (player != null && phone.openUri(link, player)) || phone.openUri(link)
        return if (opened) ToolResult.Success("Вмикаю: $what", endsConversation = true)
        else ToolResult.Failure("Немає додатка, щоб відкрити YouTube Music")
    }
}

class SavePlaylistTool(private val playlists: PlaylistStore) : Tool {

    override val name = "save_playlist"
    override val description =
        "Запам'ятати плейлист користувача з YouTube Music під назвою, щоб потім вмикати його голосом. " +
            "Потрібне посилання на плейлист (користувач ділиться ним із додатка). Якщо назви немає — спитай її."
    override val parameters = objectSchema(
        Param("name", "Назва, якою користувач кликатиме плейлист: «Музика», «Для бігу»"),
        Param("link", "Посилання на плейлист (music.youtube.com/playlist?list=…) або його id"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val playlistName = args.string("name") ?: return ToolResult.Failure("Не вказано назву плейлиста")
        val link = args.string("link") ?: return ToolResult.Failure("Не вказано посилання на плейлист")
        val id = PlaylistStore.playlistIdFrom(link)
            ?: return ToolResult.Failure("У посиланні немає id плейлиста")
        playlists.save(playlistName, id)
        return ToolResult.Success("Запам'ятав плейлист «$playlistName»")
    }
}

class MediaControlTool(private val media: MediaActions) : Tool {

    override val name = "media_control"
    override val description = "Керувати музикою чи відео, що зараз грає (у будь-якому плеєрі): пауза, продовжити, наступний, попередній трек."
    override val parameters = objectSchema(
        Param("action", "Дія", enum = listOf("play", "pause", "next", "previous", "stop")),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val (key, done) = when (args.string("action")) {
            "play" -> MediaKey.Play to "Відтворення продовжено"
            "pause" -> MediaKey.Pause to "Поставлено на паузу"
            "next" -> MediaKey.Next to "Увімкнено наступний трек"
            "previous" -> MediaKey.Previous to "Увімкнено попередній трек"
            "stop" -> MediaKey.Stop to "Зупинено"
            else -> return ToolResult.Failure("Невідома дія")
        }
        media.press(key)
        return ToolResult.Success(done)
    }
}

class SetVolumeTool(private val media: MediaActions) : Tool {

    override val name = "set_volume"
    override val description = "Змінити гучність музики й відео: гучніше, тихіше, на певний відсоток, вимкнути чи ввімкнути звук."
    override val parameters = objectSchema(
        Param("action", "up — гучніше, down — тихіше, set — встановити рівень, mute — вимкнути звук, unmute — ввімкнути", enum = listOf("up", "down", "set", "mute", "unmute")),
        Param("level", "Для set: рівень у відсотках від 0 до 100, напр. «50»", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val change = when (args.string("action")) {
            "up" -> VolumeChange.Up
            "down" -> VolumeChange.Down
            "mute" -> VolumeChange.Mute
            "unmute" -> VolumeChange.Unmute
            "set" -> VolumeChange.Set(
                args.string("level")?.filter { it.isDigit() }?.toIntOrNull()
                    ?: return ToolResult.Failure("Не вказано рівень гучності")
            )
            else -> return ToolResult.Failure("Невідома дія")
        }
        val percent = media.changeVolume(change)
        return ToolResult.Success("Гучність музики: $percent%")
    }
}
