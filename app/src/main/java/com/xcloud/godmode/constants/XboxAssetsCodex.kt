package com.xcloud.godmode.constants

object XboxAssetsCodex {
    const val VERSION = "1.0"
    
    object AgeRatings {
        object ESRB {
            const val E = "https://store-images.microsoft.com/image/global.53975.image.32437939-2f27-4e79-8431-5f5866f32908.3dc74b18-4153-423c-b367-bdcfd34ac658?q=100&w=120&h=120"
            const val E10 = "https://store-images.microsoft.com/image/global.58238.image.a7f9a1f1-3d7c-4c6e-9e8a-8e2b6e8a8e2b?q=100&w=120&h=120"
            const val T = "https://store-images.microsoft.com/image/global.17268.image.4cc004ee-a56d-4f11-ae99-67a89379b743.13d51d69-d3ba-4760-8fdf-f996abafa50a?q=100&w=120&h=120"
            const val M = "https://store-images.microsoft.com/image/global.23456.image.87f616db-3cfc-4611-b3b8-c57bbb87de71.7e7baf95-3edb-4b7c-a960-75e7537b07c9?q=100&w=120&h=120"
            
            // MS Store specific high-res IDs
            const val MS_M_MATURE = M
            const val MS_E_EVERYONE = E
            const val MS_T_TEEN = T
        }
        
        object PEGI {
            const val PEGI_3 = "https://upload.wikimedia.org/wikipedia/commons/2/23/PEGI_3.svg"
            const val PEGI_7 = "https://upload.wikimedia.org/wikipedia/commons/4/4b/PEGI_7.svg"
            const val PEGI_12 = "https://upload.wikimedia.org/wikipedia/commons/b/b3/PEGI_12.svg"
            const val PEGI_16 = "https://upload.wikimedia.org/wikipedia/commons/8/80/PEGI_16.svg"
            const val PEGI_18 = "https://upload.wikimedia.org/wikipedia/commons/9/9f/PEGI_18.svg"
        }
    }
    
    object SystemIcons {
        const val GAME_PASS = "https://upload.wikimedia.org/wikipedia/commons/9/91/Xbox_Game_Pass_logo.svg"
        
        // Iconify Fluent UI (Microsoft standard)
        const val TOUCH = "https://api.iconify.design/fluent/touch-24-regular.svg?color=%23ffffff"
        const val TOUCH_FALLBACK = "https://api.iconify.design/material-symbols/touch-app-outline-rounded.svg?color=%23ffffff"
        
        const val KEYBOARD_MOUSE = "https://api.iconify.design/fluent/keyboard-mouse-24-regular.svg?color=%23ffffff"
        const val KEYBOARD_MOUSE_FALLBACK = "https://api.iconify.design/mdi/mouse-variant.svg?color=%23ffffff"
        
        const val CONTROLLER = "https://api.iconify.design/fluent/xbox-controller-24-regular.svg?color=%23ffffff"
        const val CONTROLLER_FALLBACK = "https://api.iconify.design/mdi/controller.svg?color=%23ffffff"
        
        const val SERIES_X_S = "https://uhf.microsoft.com/images/xbox/RW8TP2.png" // User provided this specific badge
        const val OWNED_BADGE = "https://api.iconify.design/fluent/checkmark-starburst-24-filled.svg?color=%234ade80"
    }
}
