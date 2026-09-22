package dev.mahlernim.timelinevisualizer.render

import java.text.NumberFormat

/** Presentation stays separate from the numeric snapshot so locale and unit changes apply immediately. */
internal class RecapLocalizer(private val renderText: RenderText) {
    private val locale = renderText.locale
    private val language = when {
        locale.language == "zh" && (locale.country.equals("TW", true) || locale.script.equals("Hant", true)) -> "zh-TW"
        locale.language == "zh" -> "zh-CN"
        locale.language in SUPPORTED_LANGUAGES -> locale.language
        else -> "en"
    }

    val totalDistanceLabel: String get() = pick(
        "Total distance", "Gesamtstrecke", "Distancia total", "Distance totale", "総移動距離", "총 이동 거리",
        "Distância total", "总里程", "總里程", "Total jarak", "Tổng quãng đường",
    )

    val movementDaysLabel: String get() = pick(
        "Movement days", "Bewegungstage", "Días en marcha", "Jours en mouvement", "移動した日数", "이동한 날",
        "Dias em movimento", "出行天数", "移動天數", "Hari bergerak", "Ngày di chuyển",
    )

    fun formatCount(value: Int): String = number(value.toDouble(), 0)

    /** [fraction] is a share in the range 0..1, never an already multiplied percentage. */
    fun formatPercent(fraction: Double): String = NumberFormat.getPercentInstance(locale).apply {
        maximumFractionDigits = 0
    }.format(fraction.coerceIn(0.0, 1.0))

    fun transportName(mode: RecapTransportMode): String = when (mode) {
        RecapTransportMode.ON_FOOT -> pick("On foot", "Zu Fuß", "A pie", "À pied", "徒歩", "도보", "A pé", "步行", "步行", "Jalan kaki", "Đi bộ")
        RecapTransportMode.RUNNING -> pick("Running", "Laufen", "Corriendo", "Course à pied", "ランニング", "달리기", "Corrida", "跑步", "跑步", "Lari", "Chạy bộ")
        RecapTransportMode.CYCLING -> pick("Cycling", "Radfahren", "En bicicleta", "À vélo", "自転車", "자전거", "Bicicleta", "骑行", "騎自行車", "Bersepeda", "Đạp xe")
        RecapTransportMode.DRIVING -> pick("By car", "Im Auto", "En coche", "En voiture", "車", "차량", "De carro", "乘车", "搭車", "Berkendara", "Đi ô tô")
        RecapTransportMode.TRANSIT -> pick("Public transit", "Öffentlicher Verkehr", "Transporte público", "Transports publics", "公共交通", "대중교통", "Transporte público", "公共交通", "大眾運輸", "Angkutan umum", "Giao thông công cộng")
        RecapTransportMode.FLYING -> pick("Flying", "Fliegen", "En avión", "En avion", "飛行機", "항공 이동", "De avião", "乘飞机", "搭飛機", "Penerbangan", "Đi máy bay")
        RecapTransportMode.MOTORIZED -> pick("Motorized", "Motorisiert", "Motorizado", "Motorisé", "車両移動", "차량 이동", "Motorizado", "机动车", "機動車", "Kendaraan bermotor", "Phương tiện cơ giới")
        RecapTransportMode.UNKNOWN -> movementDaysLabel
    }

    fun analogy(kind: RecapAnalogyKind, count: Double): String {
        val n = number(count)
        return when (kind) {
            RecapAnalogyKind.TRACK_LAPS -> pick(
                "About $n ${if (count == 1.0) "lap" else "laps"} of a 400 m track",
                "Etwa $n Runden auf einer 400-m-Bahn",
                "Unas $n vueltas a una pista de 400 m",
                "Environ $n tours de piste de 400 m",
                "400 m トラック約 $n 周", "400m 트랙 약 ${n}바퀴",
                "Cerca de $n voltas numa pista de 400 m", "约等于 400 米跑道 $n 圈", "約等於 400 公尺跑道 $n 圈",
                "Sekitar $n putaran lintasan 400 m", "Khoảng $n vòng đường chạy 400 m",
            )
            RecapAnalogyKind.MARATHONS -> pick(
                "About $n ${if (count == 1.0) "marathon" else "marathons"}", "Etwa $n Marathons", "Unos $n maratones",
                "Environ $n marathons", "フルマラソン約 $n 回分", "마라톤 약 ${n}회",
                "Cerca de $n maratonas", "约 $n 场马拉松", "約 $n 場馬拉松", "Sekitar $n maraton", "Khoảng $n cuộc marathon",
            )
            RecapAnalogyKind.EARTH -> pick(
                "About $n ${if (count == 1.0) "trip" else "trips"} around Earth", "Etwa $n Erdumrundungen",
                "Unas $n vueltas a la Tierra", "Environ $n tours de la Terre", "地球約 $n 周", "지구 둘레 약 ${n}바퀴",
                "Cerca de $n voltas à Terra", "约绕地球 $n 圈", "約繞地球 $n 圈",
                "Sekitar $n kali keliling Bumi", "Khoảng $n vòng quanh Trái Đất",
            )
            RecapAnalogyKind.MOON -> pick(
                "About $n one-way ${if (count == 1.0) "trip" else "trips"} to the Moon", "Etwa $n einfache Reisen zum Mond",
                "Unos $n viajes de ida a la Luna", "Environ $n allers simples vers la Lune", "月まで片道約 $n 回分", "달까지 편도로 약 ${n}회",
                "Cerca de $n viagens de ida à Lua", "约 $n 次单程前往月球", "約 $n 次單程前往月球",
                "Sekitar $n perjalanan ke Bulan", "Khoảng $n chuyến một chiều tới Mặt Trăng",
            )
        }
    }

    fun personalityTitle(id: RecapPersonalityId): String = when (id) {
        RecapPersonalityId.MAIN_CHARACTER_AFTER_DARK -> pick(
            "Main Character After Dark", "Nachts die Hauptfigur", "Protagonista nocturno", "La star de la nuit", "夜の主人公", "심야의 주인공",
            "Protagonista da noite", "夜间主角", "夜間主角", "Tokoh Utama Malam Hari", "Nhân vật chính về đêm",
        )
        RecapPersonalityId.EARLY_BIRD_DLC -> pick(
            "Early Bird DLC", "Frühaufsteher-DLC", "DLC madrugador", "DLC lève-tôt", "早起き DLC", "얼리버드 DLC",
            "DLC madrugador", "早起 DLC", "早起 DLC", "DLC Si Paling Pagi", "DLC dậy sớm",
        )
        RecapPersonalityId.WEEKEND_MAIN_CHARACTER -> pick(
            "Weekend Main Character", "Wochenend-Hauptfigur", "Protagonista del finde", "La star du week-end", "週末の主人公", "주말의 주인공",
            "Protagonista do fim de semana", "周末主角", "週末主角", "Tokoh Utama Akhir Pekan", "Nhân vật chính cuối tuần",
        )
        RecapPersonalityId.ONE_DAY_PLOT_TWIST -> pick(
            "One-Day Plot Twist", "Plot-Twist an einem Tag", "Giro de guion en un día", "Tout bascule en un jour", "1日の大どんでん返し", "하루의 반전",
            "Reviravolta de um dia", "单日剧情反转", "單日劇情反轉", "Plot Twist Satu Hari", "Cú twist trong một ngày",
        )
        RecapPersonalityId.STREAK_MODE_UNLOCKED -> pick(
            "Streak Mode Unlocked", "Serienmodus aktiviert", "Modo racha desbloqueado", "Mode série débloqué", "連続記録モード解放", "연속 기록 모드 해금",
            "Modo sequência ativado", "连续模式已解锁", "連續模式已解鎖", "Mode Beruntun Terbuka", "Mở khóa chuỗi ngày",
        )
        RecapPersonalityId.LONG_HAUL_ENERGY -> pick(
            "Long-Haul Energy", "Langstrecken-Energie", "Energía de larga distancia", "Énergie longue distance", "長距離エネルギー", "장거리 에너지",
            "Energia de longa distância", "长途能量", "長途能量", "Energi Jarak Jauh", "Năng lượng đường dài",
        )
        RecapPersonalityId.CHAOS_COORDINATOR -> pick(
            "Chaos Coordinator", "Chaos-Koordinator", "Coordinador del caos", "Maître du chaos", "カオス調整役", "카오스 코디네이터",
            "Coordenador do caos", "混乱协调员", "混亂協調員", "Koordinator Kekacauan", "Điều phối viên hỗn loạn",
        )
        RecapPersonalityId.MAIN_CHARACTER_ON_THE_MOVE -> pick(
            "Main Character on the Move", "Hauptfigur unterwegs", "Protagonista en marcha", "La star en mouvement", "移動中の主人公", "이동 중인 주인공",
            "Protagonista em movimento", "移动中的主角", "移動中的主角", "Tokoh Utama Terus Bergerak", "Nhân vật chính lên đường",
        )
    }

    fun personalityEvidence(snapshot: RecapSnapshot): String {
        val value = snapshot.personalityValue
        val percent = formatPercent(value)
        val count = formatCount(value.toInt())
        return when (snapshot.personalityId) {
            RecapPersonalityId.MAIN_CHARACTER_AFTER_DARK -> pick(
                "$percent of recorded distance was between 9 pm and 5 am.",
                "$percent der erfassten Strecke lagen zwischen 21 und 5 Uhr.",
                "El $percent de la distancia registrada fue entre las 21 y las 5 h.",
                "$percent de la distance enregistrée entre 21 h et 5 h.",
                "記録された距離の $percent が21時から翌5時の移動でした。", "기록된 거리의 ${percent}가 오후 9시부터 오전 5시 사이였어요.",
                "$percent da distância registrada foi entre 21h e 5h.", "记录里程的 $percent 发生在晚上9点至次日凌晨5点。", "記錄里程的 $percent 發生在晚上9點至次日凌晨5點。",
                "$percent jarak tercatat ditempuh antara pukul 21.00 dan 05.00.", "$percent quãng đường ghi nhận nằm trong khoảng 21 giờ đến 5 giờ sáng.",
            )
            RecapPersonalityId.EARLY_BIRD_DLC -> pick(
                "$percent of recorded distance was between 5 am and 9 am.",
                "$percent der erfassten Strecke lagen zwischen 5 und 9 Uhr.",
                "El $percent de la distancia registrada fue entre las 5 y las 9 h.",
                "$percent de la distance enregistrée entre 5 h et 9 h.",
                "記録された距離の $percent が午前5時から9時の移動でした。", "기록된 거리의 ${percent}가 오전 5시부터 9시 사이였어요.",
                "$percent da distância registrada foi entre 5h e 9h.", "记录里程的 $percent 发生在早上5点至9点。", "記錄里程的 $percent 發生在早上5點至9點。",
                "$percent jarak tercatat ditempuh antara pukul 05.00 dan 09.00.", "$percent quãng đường ghi nhận nằm trong khoảng 5 giờ đến 9 giờ sáng.",
            )
            RecapPersonalityId.WEEKEND_MAIN_CHARACTER -> pick(
                "$percent of recorded distance was on Saturdays and Sundays.",
                "$percent der erfassten Strecke lagen an Samstagen und Sonntagen.",
                "El $percent de la distancia registrada fue en sábados y domingos.",
                "$percent de la distance enregistrée les samedis et dimanches.",
                "記録された距離の $percent が土日の移動でした。", "기록된 거리의 ${percent}가 토요일과 일요일의 이동이었어요.",
                "$percent da distância registrada foi aos sábados e domingos.", "记录里程的 $percent 发生在周六和周日。", "記錄里程的 $percent 發生在週六和週日。",
                "$percent jarak tercatat ditempuh pada hari Sabtu dan Minggu.", "$percent quãng đường ghi nhận rơi vào thứ Bảy và Chủ nhật.",
            )
            RecapPersonalityId.ONE_DAY_PLOT_TWIST -> pick(
                "One day accounted for $percent of your recorded distance.",
                "Ein Tag machte $percent der erfassten Strecke aus.",
                "Un solo día concentró el $percent de la distancia registrada.",
                "Une seule journée représente $percent de la distance enregistrée.",
                "1日で記録された全距離の $percent を移動しました。", "하루에 기록된 전체 거리의 ${percent}를 이동했어요.",
                "Um só dia concentrou $percent da distância registrada.", "一天就占了记录总里程的 $percent。", "一天就占了記錄總里程的 $percent。",
                "Satu hari menyumbang $percent dari seluruh jarak tercatat.", "Một ngày chiếm $percent tổng quãng đường ghi nhận.",
            )
            RecapPersonalityId.STREAK_MODE_UNLOCKED -> {
                val distance = renderText.formatDistance(5.0)
                pick(
                    "$count consecutive days with at least $distance recorded each.",
                    "$count Tage in Folge mit jeweils mindestens $distance erfasster Strecke.",
                    "$count días seguidos con al menos $distance registrados cada día.",
                    "$count jours de suite avec au moins $distance enregistrés par jour.",
                    "$count 日連続で毎日 $distance 以上の移動を記録しました。", "${count}일 연속으로 매일 $distance 이상 이동이 기록됐어요.",
                    "$count dias seguidos com pelo menos $distance registrados por dia.", "连续 $count 天每天记录至少 $distance 的移动。", "連續 $count 天每天記錄至少 $distance 的移動。",
                    "$count hari berturut-turut dengan setidaknya $distance tercatat per hari.", "$count ngày liên tiếp, mỗi ngày ghi nhận ít nhất $distance.",
                )
            }
            RecapPersonalityId.LONG_HAUL_ENERGY -> {
                val distance = renderText.formatDistance(value)
                pick(
                    "An average of $distance per day with recorded movement.",
                    "Im Schnitt $distance pro Tag mit erfasster Bewegung.",
                    "Una media de $distance por día con movimiento registrado.",
                    "En moyenne $distance par jour avec des déplacements enregistrés.",
                    "移動が記録された1日あたりの平均は $distance でした。", "이동이 기록된 날의 하루 평균 거리는 ${distance}였어요.",
                    "Média de $distance por dia com movimento registrado.", "有移动记录的日期平均每天 $distance。", "有移動記錄的日期平均每天 $distance。",
                    "Rata-rata $distance per hari dengan pergerakan tercatat.", "Trung bình $distance mỗi ngày có ghi nhận di chuyển.",
                )
            }
            RecapPersonalityId.CHAOS_COORDINATOR -> pick(
                "Your biggest day made up $percent of recorded distance.",
                "Der weiteste Tag machte $percent der erfassten Strecke aus.",
                "El día más largo sumó el $percent de la distancia registrada.",
                "La plus longue journée représente $percent de la distance enregistrée.",
                "最も移動した1日が記録された全距離の $percent を占めました。", "가장 멀리 간 하루가 기록된 전체 거리의 ${percent}를 차지했어요.",
                "O dia mais longo somou $percent da distância registrada.", "移动最多的一天占了记录里程的 $percent。", "移動最多的一天占了記錄里程的 $percent。",
                "Hari terjauh menyumbang $percent dari seluruh jarak tercatat.", "Ngày đi xa nhất chiếm $percent quãng đường ghi nhận.",
            )
            RecapPersonalityId.MAIN_CHARACTER_ON_THE_MOVE -> pick(
                "Movement recorded on $count ${if (value == 1.0) "day" else "days"}.",
                "Bewegung an $count ${if (value == 1.0) "Tag" else "Tagen"} erfasst.",
                "Movimiento registrado en $count ${if (value == 1.0) "día" else "días"}.",
                "Des déplacements enregistrés sur $count ${if (value == 1.0) "jour" else "jours"}.",
                "$count 日の移動を記録しました。", "${count}일에 걸쳐 이동이 기록됐어요.",
                "Movimento registrado em $count ${if (value == 1.0) "dia" else "dias"}.", "共 $count 天有移动记录。", "共 $count 天有移動記錄。",
                "Pergerakan tercatat selama $count hari.", "Ghi nhận di chuyển trong $count ngày.",
            )
        }
    }

    private fun number(value: Double, decimals: Int = 2): String = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = decimals
    }.format(value)

    private fun pick(
        en: String, de: String, es: String, fr: String, ja: String, ko: String,
        pt: String, zhCn: String, zhTw: String, id: String, vi: String,
    ): String = when (language) {
        "de" -> de
        "es" -> es
        "fr" -> fr
        "ja" -> ja
        "ko" -> ko
        "pt" -> pt
        "zh-CN" -> zhCn
        "zh-TW" -> zhTw
        "id" -> id
        "vi" -> vi
        else -> en
    }

    private companion object {
        val SUPPORTED_LANGUAGES = setOf("en", "de", "es", "fr", "ja", "ko", "pt", "id", "vi")
    }
}
