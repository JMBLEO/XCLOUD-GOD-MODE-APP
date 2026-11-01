// app/src/main/assets/godmode/storage/LocalCatalogStore.ts
import { CatalogItem } from '../catalog/UnifiedCatalogEngine';

export interface GameSpecificSettings {
  xboxTitleId: string;
  customProfile: 'battery_saver' | 'balanced' | 'god_mode';
  targetResolution: '720p' | '1080p' | '1440p' | '4k';
  fsrSharpness: number;
  enableVibration: boolean;
  deadzoneLeft: number;
  deadzoneRight: number;
  touchLayoutOverrides?: Record<string, { x: number; y: number; scale: number }>;
}

export class LocalCatalogStore {
  private static readonly DB_NAME = 'GodModeOfflineVault_v2';
  private static readonly DB_VERSION = 1;
  private static readonly STORE_CATALOG = 'catalog_items';
  private static readonly STORE_SETTINGS = 'game_settings';
  private static readonly STORE_META = 'metadata';

  private dbPromise: Promise<IDBDatabase>;

  constructor() {
    this.dbPromise = this.initDatabase();
  }

  private initDatabase(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      if (typeof indexedDB === 'undefined') {
        reject(new Error('IndexedDB not supported'));
        return;
      }
      const request = indexedDB.open(LocalCatalogStore.DB_NAME, LocalCatalogStore.DB_VERSION);

      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        
        // 1. Almacén de catálogo completo indexado por ProductId y TitleId
        if (!db.objectStoreNames.contains(LocalCatalogStore.STORE_CATALOG)) {
          const catStore = db.createObjectStore(LocalCatalogStore.STORE_CATALOG, { keyPath: 'productId' });
          catStore.createIndex('by_xboxTitleId', 'xboxTitleId', { unique: false });
        }

        // 2. Almacén de ajustes por juego
        if (!db.objectStoreNames.contains(LocalCatalogStore.STORE_SETTINGS)) {
          db.createObjectStore(LocalCatalogStore.STORE_SETTINGS, { keyPath: 'xboxTitleId' });
        }

        // 3. Almacén de marcas temporales y claves
        if (!db.objectStoreNames.contains(LocalCatalogStore.STORE_META)) {
          db.createObjectStore(LocalCatalogStore.STORE_META, { keyPath: 'key' });
        }
      };

      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  /**
   * Guarda o actualiza masivamente el catálogo en una única transacción atómica.
   */
  async saveCatalogBatch(items: CatalogItem[]): Promise<void> {
    const db = await this.dbPromise;
    return new Promise((resolve, reject) => {
      const tx = db.transaction([LocalCatalogStore.STORE_CATALOG, LocalCatalogStore.STORE_META], 'readwrite');
      const catStore = tx.objectStore(LocalCatalogStore.STORE_CATALOG);
      const metaStore = tx.objectStore(LocalCatalogStore.STORE_META);

      for (const item of items) {
        catStore.put(item);
      }

      metaStore.put({ key: 'last_sync_timestamp', value: Date.now() });

      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  /**
   * Recupera todos los juegos cacheados para arranque en frío inmediato (<50ms).
   */
  async getCachedCatalog(): Promise<CatalogItem[]> {
    const db = await this.dbPromise;
    return new Promise((resolve, reject) => {
      const tx = db.transaction(LocalCatalogStore.STORE_CATALOG, 'readonly');
      const store = tx.objectStore(LocalCatalogStore.STORE_CATALOG);
      const request = store.getAll();

      request.onsuccess = () => resolve(request.result || []);
      request.onerror = () => reject(request.error);
    });
  }

  /**
   * Obtiene la configuración específica de controles y shaders asociada al juego activo.
   */
  async getGameSettings(xboxTitleId: string): Promise<GameSpecificSettings | null> {
    const db = await this.dbPromise;
    return new Promise((resolve, reject) => {
      const tx = db.transaction(LocalCatalogStore.STORE_SETTINGS, 'readonly');
      const store = tx.objectStore(LocalCatalogStore.STORE_SETTINGS);
      const request = store.get(xboxTitleId);

      request.onsuccess = () => resolve(request.result || null);
      request.onerror = () => reject(request.error);
    });
  }

  /**
   * Guarda ajustes independientes para un juego concreto.
   */
  async saveGameSettings(settings: GameSpecificSettings): Promise<void> {
    const db = await this.dbPromise;
    return new Promise((resolve, reject) => {
      const tx = db.transaction(LocalCatalogStore.STORE_SETTINGS, 'readwrite');
      const store = tx.objectStore(LocalCatalogStore.STORE_SETTINGS);
      store.put(settings);

      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }
}
