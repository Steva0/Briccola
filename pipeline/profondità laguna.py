import rasterio
from rasterio.warp import calculate_default_transform, reproject, Resampling
import numpy as np
import json
import os

# --- CONFIGURAZIONE ---
TIF_FILE = r"D:\download\mappa\lagoonVe_2002_GBe.tif"
BIN_FILE = "bathymetry.bin"
JSON_META = "bathymetry_meta.json"
TARGET_CRS = 'EPSG:4326' # WGS84 (Standard GPS)

def convert_and_reproject():
    print(f"Apertura e riproiezione di {TIF_FILE} in WGS84...")
    
    with rasterio.open(TIF_FILE) as src:
        transform, width, height = calculate_default_transform(
            src.crs, TARGET_CRS, src.width, src.height, *src.bounds)
        
        kwargs = src.meta.copy()
        kwargs.update({
            'crs': TARGET_CRS,
            'transform': transform,
            'width': width,
            'height': height
        })

        # Creiamo un array temporaneo per i dati riproiettati
        dest_data = np.zeros((height, width), dtype=np.float32)

        reproject(
            source=rasterio.band(src, 1),
            destination=dest_data,
            src_transform=src.transform,
            src_crs=src.crs,
            dst_transform=transform,
            dst_crs=TARGET_CRS,
            resampling=Resampling.bilinear
        )

        print("Pulizia dati e conversione in cm (Int16)...")
        # Gestione NoData
        nodata = src.nodata if src.nodata is not None else -9999
        dest_data[dest_data == nodata] = 0
        dest_data[np.isnan(dest_data)] = 0
        
        # Convertiamo in cm positivi (Int16)
        depth_cm = (np.abs(dest_data) * 100).astype(np.int16)
        
        print(f"Salvataggio file binario: {BIN_FILE}...")
        depth_cm.tofile(BIN_FILE)
        
        # Metadati per l'app (ora in Gradi WGS84)
        meta = {
            "width": int(width),
            "height": int(height),
            "min_lon": float(transform[2]), # Longitudine dell'angolo in alto a sinistra
            "max_lat": float(transform[5]), # Latitudine dell'angolo in alto a sinistra
            "res_lon": float(transform[0]), # Gradi per pixel in orizzontale
            "res_lat": float(transform[4]), # Gradi per pixel in verticale (sarà negativo)
        }
        
        with open(JSON_META, 'w') as f:
            json.dump(meta, f, indent=2)
            
    print("\n--- CONVERSIONE E RIPROIEZIONE COMPLETATA ---")
    print(f"Il file è ora pronto per essere usato con le coordinate GPS!")
    print(f"Dimensioni finali: {os.path.getsize(BIN_FILE) / (1024*1024):.2f} MB")

if __name__ == "__main__":
    convert_and_reproject()