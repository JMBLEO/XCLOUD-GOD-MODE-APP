// app/src/main/assets/godmode/theme/ChameleonEngine.ts

export interface ChameleonTokens {
  r: number;
  g: number;
  b: number;
  accent: string;      // Color dominante en formato RGB
  glow: string;        // Sombra suave proyectada para cajas y focos
  surface: string;     // Fondo translúcido con desenfoque Glassmorphism
  border: string;      // Borde de enfoque adaptativo
  subtleBg: string;    // Gradiente ambiental profundo para el fondo global
}

export class ChameleonEngine {
  private static offscreenCanvas: HTMLCanvasElement | null = null;
  private static offscreenCtx: CanvasRenderingContext2D | null = null;

  private static initOffscreen() {
    if (!this.offscreenCanvas && typeof window !== 'undefined') {
      this.offscreenCanvas = document.createElement('canvas');
      this.offscreenCanvas.width = 16;
      this.offscreenCanvas.height = 16;
      this.offscreenCtx = this.offscreenCanvas.getContext('2d', { willReadFrequently: true });
    }
  }

  /**
   * Muestrea de forma segura un elemento HTMLImageElement o HTMLVideoElement.
   * Si las protecciones del navegador (tainted canvas) restringen getImageData,
   * conmuta sin error hacia la paleta de respaldo.
   */
  static extractPalette(
    sourceElement: HTMLImageElement | HTMLVideoElement,
    fallbackRgb: [number, number, number]
  ): ChameleonTokens {
    this.initOffscreen();
    if (!this.offscreenCtx) return this.buildTokens(fallbackRgb);

    try {
      this.offscreenCtx.drawImage(sourceElement, 0, 0, 16, 16);
      const imgData = this.offscreenCtx.getImageData(0, 0, 16, 16).data;

      let rAcc = 0;
      let gAcc = 0;
      let bAcc = 0;
      let validPixels = 0;

      for (let i = 0; i < imgData.length; i += 4) {
        const r = imgData[i];
        const g = imgData[i + 1];
        const b = imgData[i + 2];
        const sum = r + g + b;

        // Filtra los píxeles casi negros o casi blancos puros
        if (sum > 45 && sum < 710) {
          rAcc += r;
          gAcc += g;
          bAcc += b;
          validPixels++;
        }
      }

      if (validPixels > 0) {
        const rawR = Math.round(rAcc / validPixels);
        const rawG = Math.round(gAcc / validPixels);
        const rawB = Math.round(bAcc / validPixels);
        return this.buildTokens(this.normalizeVibrancy(rawR, rawG, rawB));
      }
    } catch (e) {
      // Bloqueo de seguridad CORS de navegador: aplica la paleta precalculada
    }

    return this.buildTokens(this.normalizeVibrancy(...fallbackRgb));
  }

  /**
   * Normaliza los tres canales dentro de los límites óptimos de contraste.
   */
  private static normalizeVibrancy(r: number, g: number, b: number): [number, number, number] {
    const lum = 0.299 * r + 0.587 * g + 0.114 * b;
    let nR = r;
    let nG = g;
    let nB = b;

    if (lum < 65) {
      const boost = 95 / Math.max(lum, 15);
      nR = Math.min(255, Math.round(r * boost + 22));
      nG = Math.min(255, Math.round(g * boost + 22));
      nB = Math.min(255, Math.round(b * boost + 22));
    } else if (lum > 215) {
      nR = Math.round(r * 0.75);
      nG = Math.round(g * 0.75);
      nB = Math.round(b * 0.75);
    }

    return [nR, nG, nB];
  }

  /**
   * Inyecta las variables CSS directamente en el nodo raíz :root.
   */
  static applyToCssRoot(tokens: ChameleonTokens) {
    if (typeof document === 'undefined') return;
    const root = document.documentElement;
    root.style.setProperty('--chameleon-r', String(tokens.r));
    root.style.setProperty('--chameleon-g', String(tokens.g));
    root.style.setProperty('--chameleon-b', String(tokens.b));
    root.style.setProperty('--chameleon-accent', tokens.accent);
    root.style.setProperty('--chameleon-glow', tokens.glow);
    root.style.setProperty('--chameleon-surface', tokens.surface);
    root.style.setProperty('--chameleon-border', tokens.border);
    root.style.setProperty('--chameleon-subtle-bg', tokens.subtleBg);
  }

  private static buildTokens([r, g, b]: [number, number, number]): ChameleonTokens {
    return {
      r,
      g,
      b,
      accent: `rgb(${r}, ${g}, ${b})`,
      glow: `rgba(${r}, ${g}, ${b}, 0.42)`,
      surface: `rgba(${Math.round(r * 0.22)}, ${Math.round(g * 0.22)}, ${Math.round(b * 0.22)}, 0.65)`,
      border: `rgba(${r}, ${g}, ${b}, 0.45)`,
      subtleBg: `rgba(${Math.round(r * 0.12)}, ${Math.round(g * 0.12)}, ${Math.round(b * 0.12)}, 0.9)`,
    };
  }
}
