"""
Genera i dati per la mappa offline "professionale": invece di affidarsi al meccanismo
interno/opaco di MapLibre (OfflineManager/OfflineRegion + mbgl-offline.db, che si e'
dimostrato inaffidabile quando bundlato e ricopiato tra installazioni), scarichiamo
direttamente noi le tile vettoriali/raster, sprite e glifi per l'area della laguna e le
salviamo in formati standard (mbtiles/sqlite) che l'app servira' localmente tramite un
piccolo web server embedded (vedi LocalTileServer.kt).

Bbox e zoom range replicano esattamente quelli gia' validati empiricamente nel vecchio
flusso Dev Tools (DevToolsFragment.kt: getProjectBoundsWithMargin(35_000.0), zoom 9-16).
"""
import json
import math
import os
import sqlite3
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
VECTOR_TILE_MINZOOM = 0
VECTOR_TILE_MAXZOOM = 14
RASTER_MAXZOOM = 6
MARGIN_M = 35_000.0
CONCURRENCY = 8
REQUEST_TIMEOUT = 20

OUT_VECTOR_MBTILES = "tiles_vector.mbtiles"
OUT_RASTER_MBTILES = "tiles_raster.mbtiles"
OUT_GLYPHS_DB = "glyphs.db"
OUT_STYLE_JSON = "style_liberty_offline.json"
OUT_VERSION_FILE = "tiles_version.txt"
OUT_SPRITE_FILES = [
    "sprite_ofm.json",
    "sprite_ofm.png",
    "sprite_ofm@2x.json",
    "sprite_ofm@2x.png",
]


def log(msg):
    print(f"[genera_tiles_offline] {msg}", flush=True)


# ---------------------------------------------------------------------------
# Bbox: stessa feature/formula usate da RoutingEngine.getProjectBoundsWithMargin
# ---------------------------------------------------------------------------
def calcola_bbox_con_margine():
    with open("laguna_vettoriale.json", encoding="utf-8") as f:
        data = json.load(f)
    feats = data["features"] if isinstance(data, dict) and "features" in data else data

    boundary_feat = None
    for feat in feats:
        props = feat.get("properties", {})
        if props.get("special:nav:boundary") == "project":
            boundary_feat = feat
            break
    if boundary_feat is None:
        raise RuntimeError("Feature con special:nav:boundary=project non trovata in laguna_vettoriale.json")

    def iter_coords(geom):
        t = geom["type"]
        c = geom["coordinates"]
        if t == "Polygon":
            for ring in c:
                for lon, lat in ring:
                    yield lon, lat
        elif t == "MultiPolygon":
            for poly in c:
                for ring in poly:
                    for lon, lat in ring:
                        yield lon, lat
        elif t == "LineString":
            for lon, lat in c:
                yield lon, lat
        else:
            raise RuntimeError(f"Tipo geometria non gestito per il boundary: {t}")

    lons = []
    lats = []
    for lon, lat in iter_coords(boundary_feat["geometry"]):
        lons.append(lon)
        lats.append(lat)

    min_lat, max_lat = min(lats), max(lats)
    min_lon, max_lon = min(lons), max(lons)
    mid_lat = (min_lat + max_lat) / 2.0

    lat_margin_deg = MARGIN_M / 111_320.0
    lon_margin_deg = MARGIN_M / (111_320.0 * math.cos(math.radians(mid_lat)))

    bbox = (
        min_lon - lon_margin_deg,
        min_lat - lat_margin_deg,
        max_lon + lon_margin_deg,
        max_lat + lat_margin_deg,
    )
    log(f"Bbox (lon_min, lat_min, lon_max, lat_max) = {bbox}")
    return bbox


# ---------------------------------------------------------------------------
# Slippy-map tile math
# ---------------------------------------------------------------------------
def lonlat_to_tile(lon, lat, zoom):
    lat = max(min(lat, 85.05112878), -85.05112878)
    n = 2 ** zoom
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n)
    x = max(0, min(n - 1, x))
    y = max(0, min(n - 1, y))
    return x, y


def tile_range_for_bbox(bbox, zoom):
    lon_min, lat_min, lon_max, lat_max = bbox
    x_min, y_max = lonlat_to_tile(lon_min, lat_min, zoom)
    x_max, y_min = lonlat_to_tile(lon_max, lat_max, zoom)
    return range(x_min, x_max + 1), range(y_min, y_max + 1)


# ---------------------------------------------------------------------------
# MBTiles (SQLite) helpers
# ---------------------------------------------------------------------------
def crea_mbtiles(path, format_):
    if os.path.exists(path):
        os.remove(path)
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    conn.execute(
        "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)"
    )
    conn.execute(
        "CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)"
    )
    conn.executemany(
        "INSERT INTO metadata (name, value) VALUES (?, ?)",
        [("name", "openlagunamaps"), ("format", format_), ("type", "baselayer")],
    )
    conn.commit()
    return conn


def scarica_tile_pyramid(conn, url_template, bbox, minzoom, maxzoom, label):
    session = requests.Session()
    totale_scaricate = 0
    totale_vuote = 0

    def fetch_one(z, x, y):
        url = url_template.format(z=z, x=x, y=y)
        try:
            resp = session.get(url, timeout=REQUEST_TIMEOUT)
            if resp.status_code == 200 and resp.content:
                return z, x, y, resp.content
        except requests.RequestException as e:
            log(f"  errore {label} z{z}/{x}/{y}: {e}")
        return z, x, y, None

    for z in range(minzoom, maxzoom + 1):
        xs, ys = tile_range_for_bbox(bbox, z)
        jobs = [(z, x, y) for x in xs for y in ys]
        log(f"{label}: zoom {z} -> {len(jobs)} tile da scaricare")
        with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
            futures = [pool.submit(fetch_one, *job) for job in jobs]
            rows = []
            for fut in as_completed(futures):
                zz, x, y, content = fut.result()
                if content is None:
                    totale_vuote += 1
                    continue
                tms_row = (2 ** zz - 1) - y
                rows.append((zz, x, tms_row, content))
                totale_scaricate += 1
            if rows:
                conn.executemany(
                    "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)",
                    rows,
                )
                conn.commit()
        time.sleep(0.3)  # pausa cortese tra un livello di zoom e l'altro

    log(f"{label}: completato, {totale_scaricate} tile scaricate, {totale_vuote} mancanti/errore")


# ---------------------------------------------------------------------------
# Sprite
# ---------------------------------------------------------------------------
def scarica_sprite(sprite_base_url):
    suffixes = [".json", ".png", "@2x.json", "@2x.png"]
    for suffix, out_name in zip(suffixes, OUT_SPRITE_FILES):
        url = sprite_base_url + suffix
        resp = requests.get(url, timeout=REQUEST_TIMEOUT)
        resp.raise_for_status()
        with open(out_name, "wb") as f:
            f.write(resp.content)
        log(f"Sprite salvato: {out_name} ({len(resp.content)} byte)")


# ---------------------------------------------------------------------------
# Glyphs
# ---------------------------------------------------------------------------
def estrai_fontstacks(style):
    fontstacks = set()
    for layer in style.get("layers", []):
        font = layer.get("layout", {}).get("text-font")
        if font:
            fontstacks.add(",".join(font))
    return fontstacks


def scarica_glyphs(glyphs_url_template, fontstacks):
    if os.path.exists(OUT_GLYPHS_DB):
        os.remove(OUT_GLYPHS_DB)
    conn = sqlite3.connect(OUT_GLYPHS_DB)
    conn.execute("CREATE TABLE glyphs (fontstack TEXT, range_start INTEGER, data BLOB)")
    conn.execute("CREATE UNIQUE INDEX glyphs_index ON glyphs (fontstack, range_start)")
    conn.commit()

    session = requests.Session()
    # Solo i range latini: nomi di luoghi/canali sono tutti italiani. Copre Basic Latin,
    # Latin-1 Supplement, Latin Extended-A/B (tutte le lettere accentate italiane comprese) —
    # niente CJK/arabo/cirillico/etc, che gonfierebbero il database senza alcun beneficio.
    ranges = [(0, 255), (256, 511)]

    def fetch_one(fontstack, start, end):
        url = glyphs_url_template.replace("{fontstack}", fontstack).replace("{range}", f"{start}-{end}")
        try:
            resp = session.get(url, timeout=REQUEST_TIMEOUT)
            if resp.status_code == 200 and resp.content:
                return fontstack, start, resp.content
        except requests.RequestException as e:
            log(f"  errore glifi {fontstack} {start}-{end}: {e}")
        return fontstack, start, None

    for fontstack in fontstacks:
        log(f"Glifi per fontstack '{fontstack}': {len(ranges)} range")
        rows = []
        with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
            futures = [pool.submit(fetch_one, fontstack, start, end) for start, end in ranges]
            for fut in as_completed(futures):
                fs, start, content = fut.result()
                if content is not None:
                    rows.append((fs, start, content))
        conn.executemany(
            "INSERT OR REPLACE INTO glyphs (fontstack, range_start, data) VALUES (?, ?, ?)", rows
        )
        conn.commit()
        log(f"  -> {len(rows)}/{len(ranges)} range salvati")

    conn.close()


# ---------------------------------------------------------------------------
# Style JSON locale
# ---------------------------------------------------------------------------
def genera_style_locale(style, vector_tile_template_remoto):
    style = json.loads(json.dumps(style))  # deep copy

    style["sources"]["openmaptiles"] = {
        "type": "vector",
        "tiles": ["http://127.0.0.1:__PORT__/tiles/{z}/{x}/{y}.pbf"],
        "minzoom": VECTOR_TILE_MINZOOM,
        "maxzoom": VECTOR_TILE_MAXZOOM,
    }
    if "ne2_shaded" in style["sources"]:
        style["sources"]["ne2_shaded"]["tiles"] = ["http://127.0.0.1:__PORT__/raster/{z}/{x}/{y}.png"]

    style["sprite"] = "http://127.0.0.1:__PORT__/sprite/ofm"
    style["glyphs"] = "http://127.0.0.1:__PORT__/fonts/{fontstack}/{range}.pbf"

    with open(OUT_STYLE_JSON, "w", encoding="utf-8") as f:
        json.dump(style, f)
    log(f"Style locale salvato: {OUT_STYLE_JSON}")


def main():
    start = time.time()
    bbox = calcola_bbox_con_margine()

    log("Scarico style.json...")
    style = requests.get(STYLE_URL, timeout=REQUEST_TIMEOUT).json()

    vector_tile_url = style["sources"]["openmaptiles"].get("tiles")
    if not vector_tile_url:
        tilejson_url = style["sources"]["openmaptiles"]["url"]
        tilejson = requests.get(tilejson_url, timeout=REQUEST_TIMEOUT).json()
        vector_tile_url = tilejson["tiles"]
    vector_tile_template = vector_tile_url[0]
    log(f"Template tile vettoriali: {vector_tile_template}")

    raster_tile_template = None
    if "ne2_shaded" in style["sources"]:
        raster_tile_template = style["sources"]["ne2_shaded"]["tiles"][0]
        log(f"Template tile raster: {raster_tile_template}")

    log("--- Tile vettoriali ---")
    conn_vec = crea_mbtiles(OUT_VECTOR_MBTILES, "pbf")
    scarica_tile_pyramid(conn_vec, vector_tile_template, bbox, VECTOR_TILE_MINZOOM, VECTOR_TILE_MAXZOOM, "vettoriali")
    conn_vec.close()

    if raster_tile_template:
        log("--- Tile raster di sfondo ---")
        conn_ras = crea_mbtiles(OUT_RASTER_MBTILES, "png")
        scarica_tile_pyramid(conn_ras, raster_tile_template, bbox, 0, RASTER_MAXZOOM, "raster")
        conn_ras.close()

    log("--- Sprite ---")
    scarica_sprite(style["sprite"])

    log("--- Glifi ---")
    fontstacks = estrai_fontstacks(style)
    scarica_glyphs(style["glyphs"], fontstacks)

    log("--- Style locale ---")
    genera_style_locale(style, vector_tile_template)

    # Numero di versione dei dati (non della app): serve a LocalAssetInstaller per capire se i
    # database bundlati nell'APK sono più recenti di quelli già copiati in filesDir da
    # un'installazione precedente, e forzare la ricopia in caso di aggiornamento app (altrimenti
    # un utente che aggiorna invece di reinstallare resterebbe per sempre con la mappa vecchia).
    with open(OUT_VERSION_FILE, "w", encoding="utf-8") as f:
        f.write(str(int(time.time())))
    log(f"Versione dati salvata: {OUT_VERSION_FILE}")

    log(f"Completato in {time.time() - start:.1f}s")


if __name__ == "__main__":
    main()
