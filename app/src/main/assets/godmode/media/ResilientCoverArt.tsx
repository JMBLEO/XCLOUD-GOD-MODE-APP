// app/src/main/assets/godmode/media/ResilientCoverArt.tsx
import React, { useState, useEffect, useMemo } from 'react';

export interface ResilientCoverArtProps {
  primaryUrl: string;
  fallbackUrls?: string[];
  steamAppId?: string;       // Si el juego existe en Steam, permite cargar arte Ultra-HD 600x900
  title: string;
  aspectRatio?: '2/3' | '16/9' | '1/1';
  defaultRgb: [number, number, number];
  className?: string;
  style?: React.CSSProperties;
  onColorExtracted?: (palette: [number, number, number]) => void;
}

export const ResilientCoverArt: React.FC<ResilientCoverArtProps> = ({
  primaryUrl,
  fallbackUrls = [],
  steamAppId,
  title,
  aspectRatio = '2/3',
  defaultRgb,
  className,
  style,
  onColorExtracted,
}) => {
  // Construcción de la cadena de fuentes por prioridad
  const sourcePipeline = useMemo(() => {
    const list: string[] = [];
    if (primaryUrl) list.push(primaryUrl);
    
    // Si existe AppID de Steam asociado, inyecta su CDN Akamai (baja latencia y sin CORS)
    if (steamAppId) {
      if (aspectRatio === '2/3') {
        list.push(`https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/${steamAppId}/library_600x900_2x.jpg`);
      } else if (aspectRatio === '16/9') {
        list.push(`https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/${steamAppId}/library_hero.jpg`);
      }
    }

    list.push(...fallbackUrls.filter((url) => url && url.length > 0));
    return Array.from(new Set(list));
  }, [primaryUrl, fallbackUrls, steamAppId, aspectRatio]);

  const [currentIndex, setCurrentIndex] = useState<number>(0);
  const [hasLoaded, setHasLoaded] = useState<boolean>(false);
  const [isFailed, setIsFailed] = useState<boolean>(false);

  useEffect(() => {
    setCurrentIndex(0);
    setHasLoaded(false);
    setIsFailed(false);
  }, [sourcePipeline]);

  const activeSrc = sourcePipeline[currentIndex];
  const [r, g, b] = defaultRgb;

  const handleImageError = () => {
    if (currentIndex + 1 < sourcePipeline.length) {
      setCurrentIndex((prev) => prev + 1);
    } else {
      setIsFailed(true);
    }
  };

  const handleImageLoad = (e: React.SyntheticEvent<HTMLImageElement>) => {
    setHasLoaded(true);
    if (onColorExtracted) {
      onColorExtracted(defaultRgb);
    }
  };

  return (
    <div
      className={className}
      style={{
        position: 'relative',
        width: '100%',
        aspectRatio: aspectRatio === '2/3' ? '2 / 3' : aspectRatio === '16/9' ? '16 / 9' : '1 / 1',
        overflow: 'hidden',
        backgroundColor: `rgba(${r * 0.2}, ${g * 0.2}, ${b * 0.2}, 0.85)`,
        borderRadius: 8,
        ...style,
      }}
    >
      {/* 1. Capa de Imagen Real Desinfectada de CORS */}
      {!isFailed && activeSrc && (
        <img
          src={activeSrc}
          alt={title}
          referrerPolicy="no-referrer" // Impide bloqueos de CDN por cabeceras de origen
          decoding="async"
          loading="lazy"
          onLoad={handleImageLoad}
          onError={handleImageError}
          style={{
            width: '100%',
            height: '100%',
            objectFit: 'cover',
            opacity: hasLoaded ? 1 : 0,
            transition: 'opacity 0.25s ease-in-out',
            display: 'block',
          }}
        />
      )}

      {/* 2. Capa Placeholder Geométrica (Activa mientras carga o si falla todo CDN) */}
      {(!hasLoaded || isFailed) && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'flex-end',
            padding: 12,
            background: `linear-gradient(135deg, rgba(${r},${g},${b},0.3) 0%, rgba(10,12,16,0.95) 80%)`,
          }}
        >
          {/* Logo simbólico de respaldo */}
          <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <svg width="36" height="36" viewBox="0 0 24 24" fill="rgba(255,255,255,0.25)">
              <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1.2 4.3c.4-.2.8-.3 1.2-.3s.8.1 1.2.3c1.6.8 3.2 2.4 4.1 4.1-.9-1.1-2.5-2.3-4.1-2.9l-1.2-.5-1.2.5c-1.6.6-3.2 1.8-4.1 2.9.9-1.7 2.5-3.3 4.1-4.1zm-4.9 10.6c-.8-1.4-1.2-3-1.2-4.7 0-1.1.2-2.1.6-3.1 1.3.7 3 2.1 4.4 3.8-1.6 1.2-3 2.7-3.8 4zm5.9 2.4c-1.6 0-3.1-.5-4.4-1.3.9-1.5 2.5-3.1 4.4-4.5 1.9 1.4 3.5 3 4.4 4.5-1.3.8-2.8 1.3-4.4 1.3zm6.3-2.4c-.8-1.3-2.2-2.8-3.8-4 1.4-1.7 3.1-3.1 4.4-3.8.4 1 .6 2 .6 3.1 0 1.7-.4 3.3-1.2 4.7z"/>
            </svg>
          </div>
          <span
            style={{
              fontSize: 12,
              fontWeight: 700,
              color: '#ffffff',
              textShadow: '0 2px 4px rgba(0,0,0,0.8)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {title}
          </span>
        </div>
      )}
    </div>
  );
};
