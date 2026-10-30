package com.aistudio.xcloudgodmode.ai.services

data class XboxAchievement(
    val id: String,
    val name: String,
    val description: String,
    val gamerscore: Int,
    val isUnlocked: Boolean = false,
    val isSecret: Boolean = false,
    val communityGuideTip: String = ""
)

data class GameAchievementsSummary(
    val titleId: String,
    val gameTitle: String,
    val totalGamerscore: Int,
    val currentGamerscore: Int,
    val totalAchievements: Int,
    val unlockedCount: Int,
    val achievements: List<XboxAchievement>
)

object TrueAchievementsService {

    fun getAchievementsForGame(titleId: String, gameTitle: String): GameAchievementsSummary {
        val list = when {
            gameTitle.contains("Forza", ignoreCase = true) || titleId.contains("9NKX70BBCDRN") -> listOf(
                XboxAchievement("fh5_1", "Bienvenido a México", "Llega al Festival Horizon de México.", 10, true, false, "Se desbloquea automáticamente tras la secuencia inicial de introducción."),
                XboxAchievement("fh5_2", "Pura Pasión por la Velocidad", "Supera 300 km/h en el radar de la autopista.", 25, true, false, "Usa el Koenigsegg Jesko o Bugatti Divo con tuneo de velocidad punta."),
                XboxAchievement("fh5_3", "Cazador de Graneros", "Restaura los 14 autos abandonados en graneros.", 50, false, false, "Sigue los círculos púrpuras en el mapa y escucha las transmisiones de radio."),
                XboxAchievement("fh5_4", "Salto a la Fama", "Consigue 3 estrellas en todos los saltos de peligro.", 30, false, false, "Ajusta la suspensión alta y usa tracción total AWD."),
                XboxAchievement("fh5_5", "Campeón del Salón de la Fama", "Desbloquea todos los galardones del Festival.", 100, false, true, "Requiere alcanzar 200,000 puntos de galardón.")
            )
            gameTitle.contains("Halo", ignoreCase = true) || titleId.contains("9NP1P1WFS0LB") -> listOf(
                XboxAchievement("hi_1", "Ascenso del Spartan", "Completa la primera misión de entrenamiento.", 10, true, false, "Guía rápida: Completa el tutorial de armas de la UNSC."),
                XboxAchievement("hi_2", "Tumba del Desterrado", "Derrota a Tremonius en la superficie del Anillo.", 20, true, false, "Usa el gancho para esquivar su jetpack y apunta al núcleo."),
                XboxAchievement("hi_3", "Rescate en Zeta Halo", "Salva a todos los escuadrones de Marines cautivos.", 40, false, false, "Revisa las marcas verdes FOB en el mapa táctico."),
                XboxAchievement("hi_4", "Disparo Perfecto", "Elimina a 5 enemigos con un solo disparo de Skewer.", 30, false, false, "En multijugador, apunta a vehículos ligeros como el Mongoose.")
            )
            gameTitle.contains("Gears", ignoreCase = true) -> listOf(
                XboxAchievement("gow_1", "Hermanos hasta el Fin", "Sobrevive al primer asalto del Día de la Emergencia.", 15, true, false, "Mantén la distancia y usa granadas bolo en las grietas."),
                XboxAchievement("gow_2", "Lancer en Llamas", "Realiza 50 recargas activas perfectas consecutivas.", 25, false, false, "Presiona RB justo cuando la barra alcance la marca blanca.")
            )
            else -> listOf(
                XboxAchievement("gen_1", "Primeros Pasos", "Inicia tu aventura en la nube.", 10, true, false, "Comienza la partida."),
                XboxAchievement("gen_2", "Maestro Táctico", "Completa una misión sin recibir daño crítico.", 25, false, false, "Aprovecha coberturas y curaciones."),
                XboxAchievement("gen_3", "Coleccionista Experto", "Encuentra todos los secretos del mapa.", 50, false, true, "Explora rincones secundarios y áreas ocultas.")
            )
        }

        val totalG = list.sumOf { it.gamerscore }
        val unlockedG = list.filter { it.isUnlocked }.sumOf { it.gamerscore }
        val unlockedCount = list.count { it.isUnlocked }

        return GameAchievementsSummary(
            titleId = titleId,
            gameTitle = gameTitle,
            totalGamerscore = totalG,
            currentGamerscore = unlockedG,
            totalAchievements = list.size,
            unlockedCount = unlockedCount,
            achievements = list
        )
    }
}
