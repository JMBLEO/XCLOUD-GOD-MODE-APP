// src/services/XboxMultimediaExtractor.ts

export const XBOX_OFFICIAL_DOMAINS = [
  "emerald.xboxservices.com",
  "contentaccess.exp.xboxservices.com",
  "userpresence.xboxlive.com",
  "assets.play.xbox.com",
  "notificationinbox.xboxlive.com",
  "xblmessaging.xboxlive.com",
  "rta.xboxlive.com",
  "clc.core.gssv-play-prod.xboxlive.com",
  "peoplehub.xboxlive.com",
  "chat.xboxlive.com",
  "accounts.xboxlive.com",
  "uhf.microsoft.com",
  "store-images.s-microsoft.com",
  "store-images.microsoft.com"
] as const;

export interface ImageScalingOptions {
  width?: number;
  height?: number;
  format?: 'jpg' | 'png' | 'webp';
  quality?: number;
}

export interface ChameleonPalette {
  primary: string;
  secondary: string;
  accent: string;
  background: string;
  surface: string;
}

export interface GamepassProductDetail {
  productId: string;
  title: string;
  description: string;
  publisher: string;
  developer: string;
  heroImageUrl: string;
  posterImageUrl: string;
  titledHeroUrl?: string;
  screenshots: string[];
  trailers: Array<{
    url: string;
    previewImageUrl: string;
    caption: string;
  }>;
  contentRating?: {
    rating: string;
    age: number;
    logoUrl: string;
    descriptors: string[];
    disclaimers: string[];
  };
  chameleonPalette?: ChameleonPalette;
  supportedInputTypes: string[];
}

export class XboxMultimediaExtractor {
  private static memoryCache: Map<string, string> = new Map();

  /**
   * Normaliza URLs provenientes de Microsoft Store y CDNs de Xbox
   * Maneja redirecciones CDN, esquemas relativos '//' y errores tipográficos comunes
   */
  static normalizeXboxImageUrl(
    rawUrl: string | undefined | null,
    options: ImageScalingOptions = {}
  ): string {
    if (!rawUrl || typeof rawUrl !== 'string') return '';
    const trimmed = rawUrl.trim();
    if (!trimmed) return '';

    // Chequeo rápido en caché en memoria
    const cacheKey = `${trimmed}_w${options.width || 1080}_h${options.height || 1620}`;
    if (this.memoryCache.has(cacheKey)) {
      return this.memoryCache.get(cacheKey)!;
    }

    let clean = trimmed;

    // Normalizar esquema de protocolo relativo '//'
    if (clean.startsWith('//')) {
      clean = `https:${clean}`;
    } else if (!clean.startsWith('http://') && !clean.startsWith('https://')) {
      clean = `https://${clean}`;
    }

    // Corregir errores tipográficos de dominio (tore-images -> store-images.s-microsoft.com)
    if (clean.includes('tore-images.microsoft.com') || clean.includes('tore-images.s-microsoft.com')) {
      clean = clean.replace('tore-images', 'store-images.s');
    }

    // Forzar HTTPS en store-images
    clean = clean.replace('http://store-images.s-microsoft.com', 'https://store-images.s-microsoft.com');
    clean = clean.replace('http://store-images.microsoft.com', 'https://store-images.s-microsoft.com');

    // Parámetros de escalado HD para Catálogo Xbox
    const width = options.width || 1080;
    const height = options.height || 1620;
    const format = options.format || 'jpg';
    const quality = options.quality || 92;

    const separator = clean.includes('?') ? '&' : '?';
    const scaledUrl = `${clean}${separator}w=${width}&h=${height}&format=${format}&q=${quality}`;

    this.memoryCache.set(cacheKey, scaledUrl);
    return scaledUrl;
  }

  /**
   * Extrae la paleta cromática dominante para el Chameleon Color Engine
   * Utilizado para teñir dinámicamente fondos, halos de carátulas y acentos
   */
  static extractChameleonPalette(rawProduct: any): ChameleonPalette {
    // Si la API provee paletas temáticas o fallbacks temáticos oficiales de Xbox
    const title = (rawProduct?.ProductTitle || rawProduct?.title || '').toLowerCase();
    
    if (title.includes('gears')) {
      return {
        primary: '#C53030',
        secondary: '#742A2A',
        accent: '#E53E3E',
        background: '#0F0808',
        surface: '#1A1212'
      };
    } else if (title.includes('halo')) {
      return {
        primary: '#319795',
        secondary: '#234E52',
        accent: '#4FD1C5',
        background: '#061314',
        surface: '#0F1F21'
      };
    } else if (title.includes('forza')) {
      return {
        primary: '#DD6B20',
        secondary: '#7B341E',
        accent: '#F6AD55',
        background: '#120A05',
        surface: '#1F140D'
      };
    }

    // Paleta Xbox Emerald por defecto
    return {
      primary: '#107C10',
      secondary: '#0E5C0E',
      accent: '#4ADE80',
      background: '#0B0F0C',
      surface: '#121A14'
    };
  }

  /**
   * Extrae y procesa los detalles completos de productos del catálogo de Game Pass
   * Compatible con los contratos de API emerald y catalog.gamepass.com
   */
  static parseProductDetails(productId: string, rawData: any): GamepassProductDetail {
    const raw = rawData[productId] || rawData;
    const title = raw.ProductTitle || raw.title || 'Xbox Title';
    const description = raw.ProductDescription || raw.description || '';
    const publisher = raw.PublisherName || raw.publisher || 'Xbox Game Studios';
    const developer = raw.DeveloperName || raw.developer || 'Xbox';

    // Normalizar imágenes de carátula
    const heroImageUrl = this.normalizeXboxImageUrl(
      raw.Image_Hero?.URL || raw.heroImageUrl,
      { width: 1920, height: 1080 }
    );
    const posterImageUrl = this.normalizeXboxImageUrl(
      raw.Image_Poster?.URL || raw.Image_Tile?.URL || raw.posterImageUrl,
      { width: 720, height: 1080 }
    );
    const titledHeroUrl = raw.Image_TitledHero?.URL
      ? this.normalizeXboxImageUrl(raw.Image_TitledHero.URL, { width: 1920, height: 1080 })
      : undefined;

    // Normalizar capturas de pantalla
    const screenshots: string[] = Array.isArray(raw.Screenshots)
      ? raw.Screenshots.map((s: any) => this.normalizeXboxImageUrl(s.URL, { width: 1920, height: 1080 }))
      : [];

    // Extraer trailers Smooth Streaming / MP4
    const trailers = Array.isArray(raw.Trailers)
      ? raw.Trailers.map((t: any) => ({
          url: t.URL || '',
          previewImageUrl: this.normalizeXboxImageUrl(t.PreviewImageURL, { width: 1280, height: 720 }),
          caption: t.Caption || 'Trailer Oficial'
        }))
      : [];

    // Clasificación de contenido ESRB / PEGI
    const cr = raw.ContentRating;
    const contentRating = cr ? {
      rating: cr.LongName || 'TEEN',
      age: cr.Age || 13,
      logoUrl: this.normalizeXboxImageUrl(cr.LogoUrl, { width: 120, height: 160 }),
      descriptors: Array.isArray(cr.LocalizedDescriptors) ? cr.LocalizedDescriptors : [],
      disclaimers: Array.isArray(cr.LocalizedDisclaimers) ? cr.LocalizedDisclaimers : []
    } : undefined;

    return {
      productId,
      title,
      description,
      publisher,
      developer,
      heroImageUrl,
      posterImageUrl,
      titledHeroUrl,
      screenshots,
      trailers,
      contentRating,
      chameleonPalette: this.extractChameleonPalette(raw),
      supportedInputTypes: raw.supportedInputTypes || ['Controller']
    };
  }
}
