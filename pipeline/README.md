# Pipeline dati mappa — Briccola

Questi script generano i file dati bundlati nell'app (`app/src/main/assets/`) a partire da
dati grezzi (estratti OSM, batimetria georeferenziata). Vanno eseguiti da una cartella di
lavoro locale (per convenzione `D:\download` sullo sviluppo originale) che contiene anche i
file grezzi di partenza — vedi "Dati grezzi necessari" sotto.

**Perché sono qui e non solo in `D:\download`:** prima erano *solo* in una cartella locale mai
versionata. Se quella cartella si fosse persa, nessuno script sarebbe stato recuperabile da un
semplice `git clone` di questo repo. Ora il codice della pipeline è al sicuro qui; i **dati
grezzi pesanti restano fuori da git** (troppo grandi, vedi sotto) e vanno backuppati a parte
(Drive/disco esterno).

## Ordine di esecuzione (orchestrato da `BUILD_ALL.py`)

1. `spiderman.py` — legge gli estratti OSM (`.osm`) e produce dati intermedi su canali/speciali.
2. `profondità laguna.py` — legge il raster di batimetria (`.tif`) e produce `bathymetry.bin` +
   `bathymetry_meta.json`.
3. `unire archi e profondita.py` — combina la rete di navigazione con la batimetria.
4. `laguna vettoriale.py` — produce `laguna_vettoriale.json` (canali, rocce, confine di
   progetto, aree speciali) usato sia per il rendering sia come input del passo successivo.
5. `genera_tiles_offline.py` — scarica tile vettoriali/raster/glifi/sprite da OpenFreeMap per
   l'area laguna+35km e genera `tiles_vector.mbtiles`, `tiles_raster.mbtiles`, `glyphs.db`,
   `style_liberty_offline.json`, `tiles_version.txt`, `sprite_ofm*` — richiede connessione
   internet, impiega circa 1 minuto.
6. `scarica_maree.py` — scarica/aggiorna i dati di marea astronomica.
7. `precalcola_grafo.py` — eseguito *dopo* la copia in assets (lavora su `graph.json` già
   copiato in `app/src/main/assets`), precalcola componenti connesse/Dijkstra/indice spaziale.

`BUILD_ALL.py` esegue 1-6 in ordine, copia i risultati in `app/src/main/assets/`, poi esegue 7.

## Script non nel flusso principale (usati una tantum o per feature accantonate)

- `estrai_briccole.py`, `estrai_canali.py`, `estrai_speciali.py`, `filtro laguna.py`,
  `networkmap.py`, `split_osm_data.py` — utilizzati nella fase di preparazione/pulizia degli
  estratti OSM grezzi, non richiamati automaticamente da `BUILD_ALL.py`.
- `calcola_larghezza_canali.py` — feature "canali larghi" (resa variabile in base alla
  batimetria) accantonata dopo un crash in sviluppo, mai completata. Non in uso.
- `prepara_distribuzione.py` — script di supporto per preparare pacchetti di distribuzione,
  non parte del flusso dati principale.

## Dati grezzi necessari (NON in questo repo, da backuppare separatamente)

Questi file sono troppo grandi per GitHub e contengono lavoro manuale non rigenerabile
automaticamente (georeferenziazione di carte nautiche in QGIS). **Vanno conservati con un
backup separato** (Google Drive, disco esterno) — senza, la pipeline non può ripartire da zero:

- `mappa/lagoonVe_2002_GBe.tif` e le varianti `Laguna_*_modificato.tif` / `*_low.tif` — raster
  di batimetria georeferenziati da carte nautiche ufficiali.
- `mappa/mappa laguna.osm`, `mappa/solo_tag_speciali.osm`, `mappa/laguna_essenziale.osm` —
  estratti OSM già puliti/filtrati, usati direttamente dagli script.
- Gli altri `.osm`/`.pbf`/`.pdf` in `mappa/` sono perlopiù versioni intermedie o carte di
  riferimento visuale, non strettamente necessari alla pipeline ma utili come riferimento.

## File generati (già in `app/src/main/assets/`, committati in git perché sotto i 100MB)

`bathymetry.bin`, `bathymetry_meta.json`, `graph.json`, `laguna_vettoriale.json`,
`marea_astronomica.json`, `tiles_raster.mbtiles`, `glyphs.db`, `sprite_ofm*`,
`style_liberty_offline.json`, `tiles_version.txt`.

## File generato ma ESCLUSO da git (`.gitignore`)

`tiles_vector.mbtiles` (~160MB, oltre il limite di GitHub) — rigenerabile rilanciando il passo
5 (`genera_tiles_offline.py`), serve solo connessione internet e i file già presenti in questo
repo (non serve nessun dato grezzo aggiuntivo per questo passo specifico).
