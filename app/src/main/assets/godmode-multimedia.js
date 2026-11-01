// app/src/main/assets/godmode-multimedia.js

class UnifiedCatalogEngine {
  static SIGL_ALL_CLOUD = '29a81209-df6f-41fd-a528-2ae6b91f719c';
  static MAX_BATCH_SIZE = 30;

  async fetchCloudGameIds(market = 'CL', language = 'es-CL') {
    const url = `https://catalog.gamepass.com/sigls/v2?id=${UnifiedCatalogEngine.SIGL_ALL_CLOUD}&language=${language}&market=${market}`;
    try {
      const response = await fetch(url, { method: 'GET', headers: { 'Accept': 'application/json' } });
      if (!response.ok) throw new Error(`SIGL Error: ${response.status}`);
      const data = await response.json();
      if (!Array.isArray(data)) return [];
      return data.slice(1).map(entry => entry.id).filter(id => id && id.length > 0);
    } catch (e) {
      console.warn('[GodMode] SIGL fetch failed:', e);
      return [];
    }
  }

  async hydrateCatalogItems(productIds, market = 'CL', language = 'es-CL') {
    const uniqueIds = Array.from(new Set(productIds));
    const chunks = [];
    for (let i = 0; i < uniqueIds.length; i += UnifiedCatalogEngine.MAX_BATCH_SIZE) {
      chunks.push(uniqueIds.slice(i, i + UnifiedCatalogEngine.MAX_BATCH_SIZE));
    }
    const results = [];
    for (const chunk of chunks) {
      try {
        const batchItems = await this.fetchDisplayCatalogBatch(chunk, market, language);
        results.push(...batchItems);
      } catch (err) { console.warn('[GodMode] Batch Error:', err); }
    }
    return results;
  }

  async fetchDisplayCatalogBatch(batchIds, market, language) {
    const url = `https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=${batchIds.join(',')}&market=${market}&languages=${language}&MS-CV=DGU1mcuYo0WMMp+F.1`;
    const res = await fetch(url, { method: 'GET', headers: { 'Accept': 'application/json' } });
    if (!res.ok) throw new Error(`DisplayCatalog Error: ${res.status}`);
    const data = await res.json();
    return (data.Products || []).map(prod => this.parseProductData(prod));
  }

  parseProductData(product) {
    const loc = product.LocalizedProperties?.[0] || {};
    const marketProps = product.MarketProperties?.[0] || {};
    const properties = product.Properties || {};
    const images = loc.Images || [];
    const findImg = (p) => images.find(i => i.ImagePurpose === p)?.Uri || '';
    const poster = findImg('Poster') || findImg('BoxArt');
    const titleIdObj = (product.AlternateIds || []).find(id => id.IdType === 'XboxTitleId' || id.IdType === 'LegacyXboxProductId');
    const xboxTitleId = titleIdObj?.Value || product.ProductId;
    return {
      productId: product.ProductId,
      xboxTitleId: String(xboxTitleId),
      title: loc.ProductTitle || 'Unknown',
      media: { posterUrl: this.sanitizeImageUrl(poster, 600, 900) },
      defaultRgb: this.generateStableRgb(product.ProductId)
    };
  }

  sanitizeImageUrl(rawUri, width, height, format = 'jpg') {
    if (!rawUri) return '';
    const cleanUri = rawUri.startsWith('//') ? `https:${rawUri}` : rawUri;
    return `${cleanUri}${cleanUri.includes('?') ? '&' : '?'}w=${width}&h=${height}&format=${format}&q=85`;
  }

  generateStableRgb(seed) {
    let hash = 0;
    for (let i = 0; i < seed.length; i++) hash = seed.charCodeAt(i) + ((hash << 5) - hash);
    const r = (hash & 0xFF0000) >> 16, g = (hash & 0x00FF00) >> 8, b = hash & 0x0000FF;
    return [Math.max(40, Math.min(220, r)), Math.max(40, Math.min(220, g)), Math.max(40, Math.min(220, b))];
  }
}

class LocalCatalogStore {
  constructor() { this.dbPromise = this.initDatabase(); }
  initDatabase() {
    return new Promise((resolve, reject) => {
      if (typeof indexedDB === 'undefined') return reject(new Error('No IndexedDB'));
      const request = indexedDB.open('GodModeOfflineVault_v2', 1);
      request.onupgradeneeded = (e) => {
        const db = e.target.result;
        if (!db.objectStoreNames.contains('catalog_items')) db.createObjectStore('catalog_items', { keyPath: 'productId' });
        if (!db.objectStoreNames.contains('game_settings')) db.createObjectStore('game_settings', { keyPath: 'xboxTitleId' });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  async saveCatalogBatch(items) {
    const db = await this.dbPromise;
    const tx = db.transaction(['catalog_items'], 'readwrite');
    const store = tx.objectStore('catalog_items');
    items.forEach(item => store.put(item));
  }
  async getCachedCatalog() {
    const db = await this.dbPromise;
    return new Promise((res, rej) => {
      const req = db.transaction('catalog_items', 'readonly').objectStore('catalog_items').getAll();
      req.onsuccess = () => res(req.result || []);
      req.onerror = () => rej(req.error);
    });
  }
}

class ChameleonEngine {
  static extractPalette(sourceElement, fallbackRgb) {
    const canvas = document.createElement('canvas');
    canvas.width = 16; canvas.height = 16;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    try {
      ctx.drawImage(sourceElement, 0, 0, 16, 16);
      const data = ctx.getImageData(0, 0, 16, 16).data;
      let r = 0, g = 0, b = 0, count = 0;
      for (let i = 0; i < data.length; i += 4) {
        const sum = data[i] + data[i+1] + data[i+2];
        if (sum > 45 && sum < 710) { r += data[i]; g += data[i+1]; b += data[i+2]; count++; }
      }
      return count > 0 ? [Math.round(r/count), Math.round(g/count), Math.round(b/count)] : fallbackRgb;
    } catch (e) { return fallbackRgb; }
  }
}

window.GodModeMultimedia = {
  Catalog: new UnifiedCatalogEngine(),
  Store: new LocalCatalogStore(),
  Chameleon: ChameleonEngine
};

console.log('[GodMode] Multimedia Subsystems Loaded.');
