import subprocess
import os
import shutil
import time

PROJECT_ASSETS_DIR = r"C:\Users\miche\AndroidStudioProjects\OpenLagunaMaps\app\src\main\assets"

SCRIPTS = [
    "spiderman.py",
    "profondità laguna.py",
    "unire archi e profondita.py",
    "laguna vettoriale.py",
    "genera_tiles_offline.py",
    "scarica_maree.py"
]

FILES_TO_COPY = [
    "bathymetry.bin",
    "bathymetry_meta.json",
    "graph.json",
    "laguna_vettoriale.json",
    "marea_astronomica.json",
    "tiles_vector.mbtiles",
    "tiles_raster.mbtiles",
    "glyphs.db",
    "sprite_ofm.json",
    "sprite_ofm.png",
    "sprite_ofm@2x.json",
    "sprite_ofm@2x.png",
    "style_liberty_offline.json",
    "tiles_version.txt",
]

def run_build():
    start_time = time.time()
    print("AVVIO PIPELINE DI COSTRUZIONE DATI\n")

    if not os.path.exists(PROJECT_ASSETS_DIR):
        os.makedirs(PROJECT_ASSETS_DIR)

    for script in SCRIPTS:
        print(f"--- Esecuzione: {script} ---")
        try:
            subprocess.run(["python", script], check=True)
            print(f"Completato: {script}\n")
        except subprocess.CalledProcessError:
            print(f"ERRORE durante l'esecuzione di {script}")
            return

    print("--- Copia dei file nel progetto Android ---")
    for file_name in FILES_TO_COPY:
        if os.path.exists(file_name):
            shutil.copy2(file_name, os.path.join(PROJECT_ASSETS_DIR, file_name))
            print(f"Copiato {file_name}")

    # Precalcolo dati di routing (componenti connesse, Dijkstra dai tip, indice spaziale).
    # Opera direttamente su graph.json nella cartella assets, quindi va eseguito DOPO la copia.
    print("\n--- Precalcolo dati di routing ---")
    try:
        subprocess.run(["python", "precalcola_grafo.py"], check=True)
        print("Precalcolo completato\n")
    except subprocess.CalledProcessError:
        print("ERRORE durante il precalcolo (precalcola_grafo.py)")
        return

    print(f"\nBUILD COMPLETATA CON SUCCESSO in {time.time() - start_time:.1f}s!")

if __name__ == "__main__":
    run_build()
