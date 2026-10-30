// src/services/AppStrings.ts

export interface LocalizationDictionary {
  hubTitle: string;
  xcloudTab: string;
  consolesTab: string;
  optimizerTab: string;
  settingsTab: string;
  signInXbox: string;
  signOutXbox: string;
  playNow: string;
  allIncludedSubscription: string;
  searchPlaceholder: string;
  settingsBypass: string;
  settingsStreaming: string;
  settingsRender: string;
  settingsAudio: string;
  settingsInput: string;
  settingsSystem: string;
  scanningQrTitle: string;
  scanQrPrompt: string;
  step1Prompt: string;
  step2Prompt: string;
  autoUpdateNotice: string;
  nativeLoginTitle: string;
  manualCodePrompt: string;
  redeemCode: string;
  openExternalBrowser: string;
  reloadPage: string;
  streamSettingsTitle: string;
  hudPositionLabel: string;
  hudPositionTop: string;
  hudPositionBottom: string;
  showHudLabel: string;
  visibleMetricsLabel: string;
  fsrSharpnessLabel: string;
  aspectRatioLabel: string;
  volumeBoostLabel: string;
  serverInfoTitle: string;
  serverConnectedLabel: string;
  backupExportSuccess: string;
  backupImportSuccess: string;
  backupInvalidJson: string;
  exportSettingsLabel: string;
  importSettingsLabel: string;
  languageSelectLabel: string;
  langAuto: string;
  langSpanish: string;
  langEnglish: string;
  scaleFontLabel: string;
  scaleIconLabel: string;
  scalePosterLabel: string;
  scaleQrLabel: string;
  compatibleWith: string;
  controller: string;
  mouseKeyboard: string;
  touch: string;
  languagesCount: string;
  accessibilityCount: string;
  subscriptionNotice: string;
  ageRatingLabel: string;
}

export const AppStrings: Record<"es" | "en", LocalizationDictionary> = {
  es: {
    hubTitle: "God Mode Hub",
    xcloudTab: "Catálogo xCloud",
    consolesTab: "Mis Consolas",
    optimizerTab: "Optimizador",
    settingsTab: "Ajustes Avanzados",
    signInXbox: "Iniciar Sesión Xbox",
    signOutXbox: "Cerrar Sesión",
    playNow: "JUGAR AHORA",
    allIncludedSubscription: "Todo incluido en la suscripción",
    searchPlaceholder: "Buscar título, género o desarrollador...",
    settingsBypass: "Bypass & Selección Cloud",
    settingsStreaming: "Resolución & Códecs",
    settingsRender: "Escalado de Pantalla & Shaders",
    settingsAudio: "Audio & Boost 250%",
    settingsInput: "Mando & Háptica 10-Foot",
    settingsSystem: "Idioma & Respaldo JSON",
    scanningQrTitle: "Smart TV / QR",
    scanQrPrompt: "Escanea con tu cámara",
    step1Prompt: "1. Escanea el código QR o entra a microsoft.com/link",
    step2Prompt: "2. Ingresa este código de 8 dígitos:",
    autoUpdateNotice: "Esta pantalla se actualizará automáticamente una vez que autorices el acceso.",
    nativeLoginTitle: "Nativo (WebView)",
    manualCodePrompt: "¿Iniciaste en navegador externo? Pega el código o enlace ms-xal://:",
    redeemCode: "Canjear Código",
    openExternalBrowser: "Abrir en navegador externo",
    reloadPage: "Recargar página",
    streamSettingsTitle: "Ajustes Rápidos de Stream",
    hudPositionLabel: "Posición de Estadísticas:",
    hudPositionTop: "Superior",
    hudPositionBottom: "Inferior",
    showHudLabel: "Mostrar Estadísticas (HUD):",
    visibleMetricsLabel: "Métricas Visibles:",
    fsrSharpnessLabel: "AMD FSR 3.1 RCAS:",
    aspectRatioLabel: "Relación de Aspecto:",
    volumeBoostLabel: "Volumen Boost:",
    serverInfoTitle: "Servidor & Red xCloud",
    serverConnectedLabel: "Servidor conectado:",
    backupExportSuccess: "Ajustes copiados al portapapeles exitosamente",
    backupImportSuccess: "Ajustes importados y aplicados correctamente",
    backupInvalidJson: "El formato JSON introducido no es válido",
    exportSettingsLabel: "Exportar Ajustes (JSON)",
    importSettingsLabel: "Importar Ajustes (JSON)",
    languageSelectLabel: "Idioma de la Aplicación:",
    langAuto: "Automático (Sistema)",
    langSpanish: "Español (Neutro / Latinoamérica)",
    langEnglish: "English (United States)",
    scaleFontLabel: "Tamaño de Fuente:",
    scaleIconLabel: "Tamaño de Iconos:",
    scalePosterLabel: "Tamaño de Portadas:",
    scaleQrLabel: "Tamaño de Código QR:",
    compatibleWith: "Jugar con",
    controller: "Mando",
    mouseKeyboard: "Teclado y ratón",
    touch: "Táctil",
    languagesCount: "idiomas compatibles",
    accessibilityCount: "funciones de accesibilidad",
    subscriptionNotice: "Se puede jugar en la nube con Game Pass Essential o Premium.",
    ageRatingLabel: "CLASIFICACIÓN DE CONTENIDO"
  },
  en: {
    hubTitle: "God Mode Hub",
    xcloudTab: "xCloud Library",
    consolesTab: "My Consoles",
    optimizerTab: "Optimizer",
    settingsTab: "Advanced Settings",
    signInXbox: "Sign In with Xbox",
    signOutXbox: "Sign Out",
    playNow: "PLAY NOW",
    allIncludedSubscription: "Included with subscription",
    searchPlaceholder: "Search title, genre, or developer...",
    settingsBypass: "Bypass & Cloud Selection",
    settingsStreaming: "Resolution & Codecs",
    settingsRender: "Screen Scaling & Shaders",
    settingsAudio: "Audio & Boost 250%",
    settingsInput: "Controller & 10-Foot Haptics",
    settingsSystem: "Language & JSON Backup",
    scanningQrTitle: "Smart TV / QR",
    scanQrPrompt: "Scan with your phone camera",
    step1Prompt: "1. Scan QR code or visit microsoft.com/link",
    step2Prompt: "2. Enter this 8-digit code:",
    autoUpdateNotice: "This screen will automatically refresh once you authorize access.",
    nativeLoginTitle: "Native (WebView)",
    manualCodePrompt: "Signed in via browser? Paste the ms-xal:// link or code:",
    redeemCode: "Redeem Code",
    openExternalBrowser: "Open in external browser",
    reloadPage: "Reload page",
    streamSettingsTitle: "Quick Stream Settings",
    hudPositionLabel: "Stats HUD Position:",
    hudPositionTop: "Top",
    hudPositionBottom: "Bottom",
    showHudLabel: "Show Stats HUD:",
    visibleMetricsLabel: "Visible Metrics:",
    fsrSharpnessLabel: "AMD FSR 3.1 RCAS:",
    aspectRatioLabel: "Aspect Ratio:",
    volumeBoostLabel: "Volume Boost:",
    serverInfoTitle: "xCloud Server & Network",
    serverConnectedLabel: "Connected Server:",
    backupExportSuccess: "Settings copied to clipboard successfully",
    backupImportSuccess: "Settings imported and applied successfully",
    backupInvalidJson: "The provided JSON format is invalid",
    exportSettingsLabel: "Export Settings (JSON)",
    importSettingsLabel: "Import Settings (JSON)",
    languageSelectLabel: "Application Language:",
    langAuto: "Automatic (System)",
    langSpanish: "Spanish (Neutral / Latin America)",
    langEnglish: "English (United States)",
    scaleFontLabel: "Font Size:",
    scaleIconLabel: "Icon Size:",
    scalePosterLabel: "Cover Image Size:",
    scaleQrLabel: "QR Code Size:",
    compatibleWith: "Play with",
    controller: "Controller",
    mouseKeyboard: "Keyboard & mouse",
    touch: "Touch",
    languagesCount: "supported languages",
    accessibilityCount: "accessibility features",
    subscriptionNotice: "Playable in the cloud with Game Pass Essential or Premium.",
    ageRatingLabel: "CONTENT RATING"
  }
};
