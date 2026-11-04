// app/src/main/assets/godmode/catalog/UnifiedCatalogEngine.ts

export interface GameMediaAssets {
  posterUrl: string;       // Proporción 2:3 vertical (300x450 / 600x900)
  boxArtUrl: string;       // Proporción 1:1 cuadrada (acceso directo escritorio)
  titledHeroUrl: string;   // Proporción 16:9 con título y logo integrado
  superHeroUrl: string;    // Proporción 16:9 limpia en alta definición (fondo)
  clearLogoUrl?: string;   // PNG transparente con canal alfa
  screenshots: string[];   // Capturas de pantalla oficiales en 1080p
  trailerUrl?: string;     // Flujo de video MP4 o manifiesto Smooth Streaming
}

export interface CatalogItem {
  productId: string;
  xboxTitleId: string;
  title: string;
  developer: string;
  publisher: string;
  releaseDate: string;
  description: string;
  shortDescription: string;
  categories: string[];
  contentRating?: {
    board: string;
    ratingId: string;
    logoUrl?: string;
  };
  capabilities: {
    supportsTouch: boolean;
    supportsCloudGaming: boolean;
    supportsCoOp: boolean;
    supportsMnK: boolean;
  };
  media: GameMediaAssets;
  defaultRgb: [number, number, number];
}

export class UnifiedCatalogEngine {
  // Listas SIGL oficiales de Microsoft Game Pass
  private static readonly SIGL_ALL_CLOUD = '29a81209-df6f-41fd-a528-2ae6b91f719c';
  private static readonly MAX_BATCH_SIZE = 30; // Tamaño de lote seguro para DisplayCatalog

  /**
   * Descarga la lista completa de ProductIDs disponibles en xCloud para la región del usuario.
   */
  async fetchCloudGameIds(market = 'CL', language = 'es-CL'): Promise<string[]> {
    const url = `https://catalog.gamepass.com/sigls/v2?id=${UnifiedCatalogEngine.SIGL_ALL_CLOUD}&language=${language}&market=${market}`;
    
    const response = await fetch(url, {
      method: 'GET',
      headers: { 'Accept': 'application/json' },
    });

    if (!response.ok) {
      throw new Error(`Fallo al consultar SIGL Game Pass: HTTP ${response.status}`);
    }

    const data = await response.json();
    if (!Array.isArray(data)) return [];

    // El índice 0 contiene metadatos de la lista; los elementos subsecuentes son los juegos
    return data
      .slice(1)
      .map((entry: { id?: string }) => entry.id)
      .filter((id): id is string => typeof id === 'string' && id.length > 0);
  }

  /**
   * Hidrata en paralelo y por lotes controlados los detalles técnicos desde Microsoft DisplayCatalog.
   */
  async hydrateCatalogItems(
    productIds: string[],
    market = 'CL',
    language = 'es-CL',
    onProgress?: (loaded: number, total: number) => void
  ): Promise<CatalogItem[]> {
    const uniqueIds = Array.from(new Set(productIds));
    const chunks: string[][] = [];

    for (let i = 0; i < uniqueIds.length; i += UnifiedCatalogEngine.MAX_BATCH_SIZE) {
      chunks.push(uniqueIds.slice(i, i + UnifiedCatalogEngine.MAX_BATCH_SIZE));
    }

    const results: CatalogItem[] = [];
    let loadedCount = 0;

    for (const chunk of chunks) {
      try {
        const batchItems = await this.fetchDisplayCatalogBatch(chunk, market, language);
        results.push(...batchItems);
        loadedCount += chunk.length;
        if (onProgress) onProgress(loadedCount, uniqueIds.length);
      } catch (err) {
        console.warn(`Error en lote de catálogo [${chunk.slice(0, 3).join(',')}...]:`, err);
      }
    }

    return results;
  }

  private async fetchDisplayCatalogBatch(
    batchIds: string[],
    market: string,
    language: string
  ): Promise<CatalogItem[]> {
    const joinedIds = batchIds.join(',');
    const url = `https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=${joinedIds}&market=${market}&languages=${language}&MS-CV=DGU1mcuYo0WMMp+F.1`;

    const res = await fetch(url, {
      method: 'GET',
      headers: { 'Accept': 'application/json' },
    });

    if (!res.ok) {
      throw new Error(`DisplayCatalog error HTTP ${res.status}`);
    }

    const data = await res.json();
    const products: any[] = data.Products || [];

    return products.map((prod) => this.parseProductData(prod));
  }

  /**
   * Mapea y sanea los esquemas complejos de DisplayCatalog a una estructura plana normalizada.
   */
  private parseProductData(product: any): CatalogItem {
    const loc = product.LocalizedProperties?.[0] || {};
    const marketProps = product.MarketProperties?.[0] || {};
    const properties = product.Properties || {};
    const images: any[] = loc.Images || [];
    const videos: any[] = loc.CMSVideos || loc.Videos || [];

    // Búsqueda por rol de imagen (ImagePurpose)
    const findImg = (purpose: string): string =>
      images.find((img) => img.ImagePurpose === purpose)?.Uri || '';

    const posterRaw = findImg('Poster') || findImg('BoxArt');
    const boxArtRaw = findImg('BoxArt') || posterRaw;
    const titledHeroRaw = findImg('TitledHeroArt') || findImg('SuperHeroArt');
    const superHeroRaw = findImg('SuperHeroArt') || titledHeroRaw;
    const logoRaw = findImg('Logo');

    const screenshots = images
      .filter((img) => img.ImagePurpose === 'Screenshot')
      .map((img) => this.sanitizeImageUrl(img.Uri, 1920, 1080));

    // Extracción de video / tráiler oficial
    let trailerUrl: string | undefined;
    if (videos.length > 0) {
      const vid = videos[0];
      const files: any[] = vid.Files || [];
      const mp4 = files.find((f) => f.FileFormat === 'MP4' || f.Uri?.endsWith('.mp4'));
      trailerUrl = mp4?.Uri || vid.URI;
    }

    // Extracción obligatoria de xboxTitleId para el handshake del pipeline nativo Zero-Copy
    const alternateIds: any[] = product.AlternateIds || [];
    const titleIdObj =
      alternateIds.find((id) => id.IdType === 'XboxTitleId') ||
      alternateIds.find((id) => id.IdType === 'LegacyXboxProductId');
    const xboxTitleId = titleIdObj?.Value || product.ProductId;

    // Detección de capacidades del juego
    const attributes: any[] = properties.Attributes || [];
    const hasAttr = (names: string[]): boolean =>
      attributes.some((attr) => names.includes(attr.Name));

    const contentRatings = marketProps.ContentRatings?.[0];

    return {
      productId: product.ProductId,
      xboxTitleId: String(xboxTitleId),
      title: loc.ProductTitle || 'Título desconocido',
      developer: loc.DeveloperName || 'Estudio no especificado',
      publisher: loc.PublisherName || 'Xbox Game Studios',
      releaseDate: marketProps.OriginalReleaseDate || '',
      description: loc.ProductDescription || '',
      shortDescription: loc.ShortDescription || loc.ProductDescription || '',
      categories: properties.Categories || [properties.Category].filter(Boolean),
      contentRating: contentRatings ? {
        board: contentRatings.RatingSystem || 'ESRB',
        ratingId: contentRatings.RatingId || '',
        logoUrl: contentRatings.RatingValueLogoUri ? this.sanitizeImageUrl(contentRatings.RatingValueLogoUri, 128, 128, 'png') : undefined,
      } : undefined,
      capabilities: {
        supportsTouch: hasAttr(['TouchEnabled', 'XblTouch', 'NativeTouch']),
        supportsCloudGaming: true,
        supportsCoOp: hasAttr(['SharedSplitScreen', 'XblLocalCoop', 'LocalMultiplayer']),
        supportsMnK: hasAttr(['KeyboardMouseSupport', 'NativeMouseKeyboard']),
      },
      media: {
        posterUrl: this.sanitizeImageUrl(posterRaw, 600, 900),
        boxArtUrl: this.sanitizeImageUrl(boxArtRaw, 400, 400),
        titledHeroUrl: this.sanitizeImageUrl(titledHeroRaw, 1280, 720),
        superHeroUrl: this.sanitizeImageUrl(superHeroRaw, 1920, 1080),
        clearLogoUrl: logoRaw ? this.sanitizeImageUrl(logoRaw, 800, 400, 'png') : undefined,
        screenshots,
        trailerUrl,
      },
      defaultRgb: this.generateStableRgb(product.ProductId),
    };
  }

  /**
   * Resuelve esquemas relativos //, inyecta HTTPS y asigna parámetros dinámicos de tamaño y formato.
   */
  sanitizeImageUrl(rawUri: string, width: number, height: number, format: 'jpg' | 'png' | 'webp' = 'jpg'): string {
    if (!rawUri) return '';
    const cleanUri = rawUri.startsWith('//') ? `https:${rawUri}` : rawUri;
    const separator = cleanUri.includes('?') ? '&' : '?';
    return `${cleanUri}${separator}w=${width}&h=${height}&format=${format}&q=85`;
  }

  /**
   * Genera una paleta de color semilla determinista a partir del ProductId en caso de bloqueo de red.
   */
  private generateStableRgb(seed: string): [number, number, number] {
    let hash = 0;
    for (let i = 0; i < seed.length; i++) {
      hash = seed.charCodeAt(i) + ((hash << 5) - hash);
    }
    const r = (hash & 0xFF0000) >> 16;
    const g = (hash & 0x00FF00) >> 8;
    const b = hash & 0x0000FF;
    return [Math.max(40, Math.min(220, r)), Math.max(40, Math.min(220, g)), Math.max(40, Math.min(220, b))];
  }
}
