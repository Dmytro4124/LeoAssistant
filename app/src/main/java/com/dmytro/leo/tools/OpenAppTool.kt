package com.dmytro.leo.tools

import com.dmytro.leo.data.AppMatcher
import com.dmytro.leo.data.InstalledApps
import com.dmytro.leo.data.PreferredApps
import kotlinx.serialization.json.JsonObject

class OpenAppTool(
    private val apps: InstalledApps,
    private val phone: PhoneActions,
) : Tool {

    override val name = "open_app"
    override val description =
        "Відкрити встановлений на телефоні додаток за назвою. " +
            "Якщо точного збігу немає, повертає схожі назви: якщо одна з них очевидно та сама програма " +
            "(напр. «YT Music» для YouTube Music), одразу виклич open_app ще раз з цією назвою; інакше перепитай користувача."
    override val parameters = objectSchema(
        Param("name", "Назва додатка так, як вона зазвичай пишеться: YouTube Music, Google Maps, Telegram, Камера, Налаштування"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.string("name") ?: return ToolResult.Failure("Не вказано назву додатка")

        return when (val match = AppMatcher.find(query, PreferredApps.apply(apps.list()))) {
            is AppMatcher.Match.Found ->
                if (phone.openApp(match.app.packageName)) ToolResult.Success("Відкрито ${match.app.label}")
                else ToolResult.Failure("${match.app.label} не вдалося запустити")
            is AppMatcher.Match.Unsure ->
                if (match.candidates.isEmpty()) ToolResult.Failure("Додаток «$query» не знайдено на телефоні")
                else ToolResult.Failure(
                    "Нічого не відкрито: точного збігу для «$query» немає. Схожі: ${match.candidates.joinToString { it.label }}. " +
                        "Якщо одна з них підходить, виклич open_app з її назвою."
                )
        }
    }
}
