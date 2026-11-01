package com.xcloud.godmode.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object XboxUnifiedIcons {
    val Settings = Icons.Default.Settings
    val Library = Icons.AutoMirrored.Filled.LibraryBooks
    val Remote = Icons.Default.Cast
    val Refresh = Icons.Default.Refresh
    val Power = Icons.Default.PowerSettingsNew
    val Info = Icons.Default.Info
    val Favorite = Icons.Default.Favorite
    val FavoriteBorder = Icons.Default.FavoriteBorder
    val Search = Icons.Default.Search
    val TopArrow = Icons.Default.ArrowUpward
    val XboxSeries = Icons.Default.Bolt

    // Custom Xbox Series X Console Icon (Relief Style)
    val Console: ImageVector by lazy {
        ImageVector.Builder(
            name = "XboxSeriesXConsole",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.White),
            strokeLineWidth = 1.2f
        ) {
            // Main Chassis
            moveTo(7f, 3f)
            lineTo(17f, 3f)
            lineTo(17f, 21f)
            lineTo(7f, 21f)
            close()
            
            // Vent Grill (Top Relief Dots)
            moveTo(9f, 4.5f)
            lineTo(15f, 4.5f)
            moveTo(9f, 5.5f)
            lineTo(15f, 5.5f)
            moveTo(9f, 6.5f)
            lineTo(15f, 6.5f)
            
            // Disc Slot (Vertical left)
            moveTo(8.5f, 13f)
            lineTo(8.5f, 18f)
            
            // Power Button
            moveTo(11.5f, 5f)
            lineTo(12.5f, 5f)
            
            // USB Port
            moveTo(14.5f, 19f)
            lineTo(15.5f, 19f)
        }.build()
    }

    // Official SVG Paths from Xbox Web Client
    val GamePass: ImageVector by lazy {
        ImageVector.Builder(
            name = "GamePass",
            defaultWidth = 2048.dp,
            defaultHeight = 1280.dp,
            viewportWidth = 2048f,
            viewportHeight = 2048f
        ).path(fill = null, stroke = null) {
            addPathNodes("M0 384h2048v1280H0V384zm684 160L536 974h146l20-78h120l19 78h146L848 544H684zM88 766q0 113 59 164t169 51q10 0 35-3t52-8 50-11 28-14V711H266v104h81v53q-8 1-14 1t-15 1q-50 0-72-31t-23-77q0-53 28-83t83-31q34 0 60 7t58 18V553h-3q-31-5-59-10t-60-6q-33 0-65 6t-60 18-53 33-41 51q-16 27-19 58t-4 63zm491 450q0-42-13-70t-37-44-56-22-70-7H226v430h135v-136h35q35 0 68-9t58-27 41-47 16-68zm320 288h147l-140-430H743l-148 430h146l20-77h119l19 77zm529-131q0-33-13-55t-32-39-42-26-42-19-32-18-13-21q0-16 15-20t28-5q26 0 57 8t52 24v-118h-3q-31-5-58-11t-60-6q-33 0-64 8t-56 24-40 43-15 64q0 35 12 57t31 38 41 25 40 18 31 17 13 23q0 16-14 20t-26 5q-33 0-65-14t-58-33v126q33 10 62 17t64 7q33 0 66-7t61-23 43-42 17-67zm5-696q-1 15-1 30t0 29v238h130V544h-191q-16 68-33 135t-29 136q-3-10-4-19t-3-20l-58-229-1-3h-197v430h119V771q0-23-1-46t-2-46q13 75 34 148t40 147h126l63-257 4-20 4-20zm395 698q0-33-13-55t-32-38-42-26-42-20-32-17-13-21q0-15 15-20t28-6q31 0 56 9t53 23v-117q-26-11-59-14t-62-3q-33 0-64 8t-56 24-40 43-15 64q0 34 12 57t31 38 41 24 40 18 31 18 13 22q0 16-13 21t-27 5q-35 0-64-14t-58-33v126q11 6 27 10t34 8 35 4 29 1q34 0 67-6t60-23 43-43 17-67zm106-401V867h-137v-55h127V705h-127v-54h135V544h-270v430h272zM820 1187q7 35 16 70t19 71h-71q9-35 20-70t16-71zm-23-389h-71l36-141q8 36 17 71t18 70zm-415 374q24 0 39 11t16 38q0 25-13 36t-38 12h-25v-97h21z")
        }.build()
    }

    val Controller: ImageVector by lazy {
        ImageVector.Builder(
            name = "Controller",
            defaultWidth = 2048.dp,
            defaultHeight = 2048.dp,
            viewportWidth = 2048f,
            viewportHeight = 2048f
        ).path(fill = null, stroke = null) {
            addPathNodes("M1024 640q26 0 45 19t19 45q0 26-19 45t-45 19q-26 0-45-19t-19-45q0-26 19-45t45-19zm704-164q20 14 36 35t25 45l224 607q20 55 27 121t8 124q0 55-17 111t-49 103-80 76-110 30q-22 0-48-8t-43-24l-304-304q-12-12-30-21t-40-15-42-9-37-3H800q-17 0-37 3t-42 9-39 15-31 21l-304 304q-16 16-42 24t-49 8q-62 0-109-29t-80-76-50-103-17-112q0-61 7-124t28-121l1-1 225-609v-2q9-22 24-41t35-34v-66q44-23 85-40t84-28 88-16 95-6q34 0 61 10t49 22 42 22 40 10h320q19 0 39-10t42-22 50-22 61-10q50 0 95 5t87 17 84 28 86 40v66zm64 1124q33 0 57-20t40-49 23-62 8-61q0-49-6-102t-22-101q-32-93-66-184t-70-184l-61-167q-7-17-12-35t-14-35q-5-10-12-17-11-11-27-17t-30-12v-66q-53-22-110-31t-114-9q-20 0-39 10t-42 22-50 22-61 10H864q-34 0-61-10t-49-22-43-22-39-10q-57 0-114 9t-110 31v66q-8 4-18 8t-21 9-18 12-12 17l-223 605q-16 44-22 100t-6 103q0 27 7 60t23 63 40 49 58 20q5 0 7-1l302-303q20-20 49-35t61-25 65-15 60-5h448q27 0 59 5t65 15 62 25 49 35l302 303q2 1 7 1z")
        }.build()
    }

    val Touch: ImageVector by lazy {
        ImageVector.Builder(
            name = "Touch",
            defaultWidth = 2048.dp,
            defaultHeight = 2048.dp,
            viewportWidth = 2048f,
            viewportHeight = 2048f
        ).path(fill = null, stroke = null) {
            addPathNodes("M1600 896q40 0 75 15t61 41 41 61 15 75v384q0 119-45 224t-124 183-183 123-224 46q-144 0-268-55t-226-156l-472-472q-28-28-43-65t-15-76q0-42 16-78t43-64 63-42 78-16q82 0 141 59l107 106V853q-59-28-106-70t-80-95-52-114-18-126q0-93 35-174t96-143 142-96T832 0q93 0 174 35t143 96 96 142 35 175q0 93-37 178t-105 149q35 9 63 30t49 52q45-25 94-25 50 0 93 23t69 66q45-25 94-25zM512 448q0 75 34 143t94 113V448q0-40 15-75t41-61 61-41 75-15q40 0 75 15t61 41 41 61 15 75v256q60-45 94-113t34-143q0-66-25-124t-69-101-102-69-124-26q-66 0-124 25t-102 69-69 102-25 124zm1152 1024v-384q0-26-19-45t-45-19q-21 0-34 7t-20 21-9 31-2 37q0 16 1 32t1 32q0 20-2 37t-9 30-20 21-34 8q-21 0-34-8t-20-21-9-33-3-40q0-28 2-58t2-58q0-43-12-72t-54-30q-21 0-34 7t-20 21-9 31-2 37q0 16 1 32t1 32q0 20-2 37t-9 30-20 21-34 8q-21 0-34-8t-20-21-9-33-3-40q0-28 2-58t2-58q0-43-12-72t-54-30q-21 0-34 7t-20 21-9 31-2 37q0 16 1 32t1 32q0 20-2 37t-9 30-20 21-34 8q-26 0-45-19t-19-45V448q0-26-19-45t-45-19q-26 0-45 19t-19 45v787q0 23-8 42t-23 35-35 23-42 9q-22 0-42-8t-37-24l-139-139q-21-21-50-21t-50 21-22 51q0 29 21 50l472 473q84 84 184 128t219 45q93 0 174-35t142-96 96-142 36-175z")
        }.build()
    }

    val Owned: ImageVector by lazy {
        ImageVector.Builder(
            name = "Owned",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f
        ).path(fill = null, stroke = null) {
            addPathNodes("M16 0c1.467 0 2.878.189 4.233.567a15.9 15.9 0 013.817 1.616 16.209 16.209 0 015.75 5.75 16.43 16.43 0 011.617 3.817c.389 1.367.583 2.783.583 4.25s-.189 2.878-.567 4.233a16.212 16.212 0 01-1.616 3.834c-.7 1.2-1.534 2.277-2.5 3.233a16.74 16.74 0 01-3.25 2.5c-1.2.711-2.473 1.25-3.817 1.617A17.06 17.06 0 0116 32a15.758 15.758 0 01-8.067-2.183A16.117 16.117 0 012.2 24.083 15.832 15.832 0 01.583 20.25 16.347 16.347 0 010 16c0-1.467.189-2.878.567-4.233a16.212 16.212 0 011.616-3.834c.7-1.2 1.534-2.277 2.5-3.233a16.74 16.74 0 013.25-2.5c1.2-.711 2.473-1.25 3.817-1.617A17.06 17.06 0 0116 0zm6.204 10.379l-8.677 8.676-3.93-3.93-.121-.109-.155-.11c-.664-.407-1.489-.313-2.021.22a1.625 1.625 0 000 2.298l5.079 5.079.121.109.155.111c.664.406 1.49.312 2.022-.22l9.825-9.826.11-.122.11-.154c.407-.664.313-1.49-.22-2.022a1.625 1.625 0 00-2.298 0z")
        }.build()
    }

    val Keyboard: ImageVector by lazy {
        ImageVector.Builder(
            name = "Keyboard",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f
        ).path(fill = null, stroke = null) {
            addPathNodes("M2.438 25.688c.468.208.963.312 1.484.312H18v-2H4a2.41 2.41 0 01-.797-.156 1.704 1.704 0 01-.625-.422 2.207 2.207 0 01-.422-.64C2.052 22.541 2 22.281 2 22V8c-.01-.27.042-.531.156-.781.115-.25.26-.464.438-.64.177-.178.385-.319.625-.423.24-.104.5-.156.781-.156h24c.27 0 .531.052.781.156.25.104.464.25.64.438.178.187.319.4.423.64A1.9 1.9 0 0130 8v4.402a6.2 6.2 0 012 1.237V7.922a3.84 3.84 0 00-.328-1.5 4.12 4.12 0 00-.86-1.25 4.12 4.12 0 00-1.25-.86A3.614 3.614 0 0028.078 4H3.922a3.84 3.84 0 00-1.5.328 4.12 4.12 0 00-1.25.86 4.12 4.12 0 00-.86 1.25A3.614 3.614 0 000 7.921v14.156c.01.531.12 1.031.328 1.5.208.469.495.886.86 1.25a4.12 4.12 0 001.25.86z M6.297 20.297A.961.961 0 017 20h11v2H7a.961.961 0 01-.703-.297A.961.961 0 016 21c0-.27.099-.505.297-.703zM24.5 12c.417 0 .77-.146 1.063-.438.291-.291.437-.645.437-1.062 0-.417-.146-.77-.438-1.063A1.447 1.447 0 0024.5 9c-.417 0-.77.146-1.063.438A1.446 1.446 0 0023 10.5c0 .417.146.77.438 1.063.291.291.645.437 1.062.437zM5.438 9.438A1.446 1.446 0 005 10.5c0 .417.146.77.438 1.063.291.291.645.437 1.062.437.417 0 .77-.146 1.063-.438.291-.291.437-.645.437-1.062 0-.417-.146-.77-.438-1.063A1.447 1.447 0 006.5 9c-.417 0-.77.146-1.063.438zM11.438 9.438A1.446 1.446 0 0011 10.5c0 .417.146.77.438 1.063.291.291.645.437 1.062.437.417 0 .77-.146 1.063-.438.291-.291.437-.645.437-1.062 0-.417-.146-.77-.438-1.063A1.447 1.447 0 0012.5 9c-.417 0-.77.146-1.063.438zM17.438 9.438A1.446 1.446 0 0017 10.5c0 .417.146.77.438 1.063.291.291.645.437 1.062.437.417 0 .77-.146 1.063-.438.291-.291.437-.645.437-1.062 0-.417-.146-.77-.438-1.063A1.447 1.447 0 0018.5 9c-.417 0-.77.146-1.063.438zM9.438 15.438A1.447 1.447 0 009 16.5c0 .417.146.77.438 1.063.291.291.645.437 1.062.437.417 0 .77-.146 1.063-.438.291-.291.437-.645.437-1.062 0-.417-.146-.77-.438-1.063A1.446 1.446 0 0010.5 15c-.417 0-.77.146-1.063.438zM15.438 15.438A1.446 1.446 0 0015 16.5c0 .417.146.77.438 1.063.291.291.645.437 1.062.437.417 0 .77-.146 1.063-.438.291-.291.437-.645.437-1.062 0-.417-.146-.77-.438-1.063A1.446 1.446 0 0016.5 15c-.417 0-.77.146-1.063.438z M20 18.2a4.2 4.2 0 014.2-4.2h3.6a4.2 4.2 0 014.2 4.2V26a6 6 0 01-12 0v-7.8zm4.2-2.2a2.2 2.2 0 00-2.2 2.2V26a4 4 0 008 0v-7.8a2.2 2.2 0 00-2.2-2.2H27v6a1 1 0 11-2 0v-6h-.8z")
        }.build()
    }
}
