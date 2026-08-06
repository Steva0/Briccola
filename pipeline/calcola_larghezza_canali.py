import json
import math

import numpy as np
from pyproj import Transformer
from shapely.geometry import LineString

INPUT_LAGUNA = "laguna_vettoriale.json"
INPUT_BATHY_META = "bathymetry_meta.json"
INPUT_BATHY_BIN = "bathymetry.bin"
OUTPUT_FILE = "canali_larghi.json"

STEP_M = 7.0          # passo di ricampionamento lungo il canale
SEARCH_STEP_M = 1.0   # passo di ricerca laterale
SEARCH_CAP_M = 20.0   # oltre questa distanza laterale non cerchiamo più (= max slider in app)
MAX_SLOPE_STEP_M = 2.0  # quanto può allargarsi/restringersi al massimo da un punto al successivo

LOCAL_CRS = "EPSG:3004"  # Gauss-Boaga, stesso piano metrico già usato altrove nella pipeline
WGS84 = "EPSG:4326"


def load_bathymetry():
    with open(INPUT_BATHY_META, encoding="utf-8") as f:
        meta = json.load(f)
    width = meta["width"]
    height = meta["height"]
    min_lon = meta["min_lon"]
    max_lat = meta["max_lat"]
    res_lon = meta["res_lon"]
    res_lat = meta["res_lat"]
    data = np.fromfile(INPUT_BATHY_BIN, dtype="<i2")  # Int16 little-endian
    data = data.reshape((height, width))
    return data, width, height, min_lon, max_lat, res_lon, res_lat


def make_depth_fn(data, width, height, min_lon, max_lat, res_lon, res_lat):
    """Stessa identica logica di BathymetryEngine.getDepthAt (Kotlin): nearest-neighbor per
    troncamento indici, cm->metri, soglia <=0.05m -> 0. Nessun controllo no-go areas (non
    rilevante qui: stiamo solo misurando quanta acqua reale c'è secondo il raster grezzo)."""
    def depth_at(lat: float, lon: float) -> float:
        x = int((lon - min_lon) / res_lon)
        y = int((lat - max_lat) / res_lat)
        if not (0 <= x < width) or not (0 <= y < height):
            return 0.0
        depth_cm = int(data[y, x])
        depth = depth_cm / 100.0
        return 0.0 if depth <= 0.05 else depth
    return depth_at


def resample_line(coords_lonlat, to_local, to_wgs84):
    """Ricampiona la linea (in coordinate metriche locali) ogni STEP_M, restituendo per ogni
    punto: (lon, lat) WGS84 e la tangente locale (dx, dy) normalizzata."""
    xs, ys = to_local.transform(
        [c[0] for c in coords_lonlat], [c[1] for c in coords_lonlat]
    )
    line_local = LineString(zip(xs, ys))
    length = line_local.length
    if length == 0:
        return []

    n_steps = max(1, int(length // STEP_M))
    distances = [i * STEP_M for i in range(n_steps + 1)]
    if distances[-1] < length:
        distances.append(length)

    points_local = [line_local.interpolate(d) for d in distances]

    results = []
    for i, p in enumerate(points_local):
        if i == 0:
            nxt = points_local[i + 1]
            dx, dy = nxt.x - p.x, nxt.y - p.y
        elif i == len(points_local) - 1:
            prev = points_local[i - 1]
            dx, dy = p.x - prev.x, p.y - prev.y
        else:
            prev = points_local[i - 1]
            nxt = points_local[i + 1]
            dx, dy = nxt.x - prev.x, nxt.y - prev.y

        norm = math.hypot(dx, dy)
        if norm == 0:
            tangent = (1.0, 0.0)
        else:
            tangent = (dx / norm, dy / norm)

        lon, lat = to_wgs84.transform(p.x, p.y)
        results.append((lon, lat, p.x, p.y, tangent))

    return results


def smooth_slope_limited(values, max_step):
    """Limita quanto la larghezza può variare da un punto al successivo (in entrambe le
    direzioni): un punto stretto "tira verso il basso" anche i vicini, invece di un salto netto
    da un tratto stretto a uno largo (che nei canali di Venezia dava bordi frastagliati, molto
    visibili nel centro storico). Tecnica standard: passata in avanti + passata all'indietro,
    ciascuna limita l'aumento rispetto al vicino appena processato a max_step."""
    n = len(values)
    if n == 0:
        return values
    smoothed = list(values)
    for i in range(1, n):
        smoothed[i] = min(smoothed[i], smoothed[i - 1] + max_step)
    for i in range(n - 2, -1, -1):
        smoothed[i] = min(smoothed[i], smoothed[i + 1] + max_step)
    return smoothed


def measure_side(px, py, perp, depth_at, to_wgs84):
    """Marcia lungo la perpendicolare (perp = (dx, dy) normalizzato, in metri locali) fino a
    SEARCH_CAP_M, trova la distanza massima CONTIGUA con acqua reale a partire dal centro."""
    offset = 0.0
    while offset < SEARCH_CAP_M:
        next_offset = min(offset + SEARCH_STEP_M, SEARCH_CAP_M)
        test_x = px + perp[0] * next_offset
        test_y = py + perp[1] * next_offset
        lon, lat = to_wgs84.transform(test_x, test_y)
        if depth_at(lat, lon) <= 0.0:
            return offset
        offset = next_offset
    return SEARCH_CAP_M


def run():
    data, width, height, min_lon, max_lat, res_lon, res_lat = load_bathymetry()
    depth_at = make_depth_fn(data, width, height, min_lon, max_lat, res_lon, res_lat)

    to_local = Transformer.from_crs(WGS84, LOCAL_CRS, always_xy=True)
    to_wgs84 = Transformer.from_crs(LOCAL_CRS, WGS84, always_xy=True)

    with open(INPUT_LAGUNA, encoding="utf-8") as f:
        laguna = json.load(f)

    canali_out = []
    for idx, feature in enumerate(laguna["features"]):
        props = feature.get("properties", {})
        if props.get("type") != "canal":
            continue
        coords = feature["geometry"]["coordinates"]
        if len(coords) < 2:
            continue

        sampled = resample_line(coords, to_local, to_wgs84)
        if not sampled:
            continue

        lons_lats = []
        lefts_raw = []
        rights_raw = []
        for lon, lat, px, py, (tx, ty) in sampled:
            # perpendicolare = tangente ruotata di 90°
            perp_left = (-ty, tx)
            perp_right = (ty, -tx)
            lefts_raw.append(measure_side(px, py, perp_left, depth_at, to_wgs84))
            rights_raw.append(measure_side(px, py, perp_right, depth_at, to_wgs84))
            lons_lats.append((lon, lat))

        # Smoothing: senza questo, un punto stretto seguito subito da uno largo (rumore tipico
        # della batimetria nearest-neighbor) produce un salto netto nel poligono — molto visibile
        # nei canali stretti del centro storico. Si parte quindi dai punti più stretti e si
        # "tira giù" gradualmente i vicini invece di un bordo frastagliato.
        lefts = smooth_slope_limited(lefts_raw, MAX_SLOPE_STEP_M)
        rights = smooth_slope_limited(rights_raw, MAX_SLOPE_STEP_M)

        points_out = [
            {"lat": round(lat, 7), "lon": round(lon, 7), "left_m": round(l, 1), "right_m": round(r, 1)}
            for (lon, lat), l, r in zip(lons_lats, lefts, rights)
        ]

        canal_id = props.get("id") or str(idx)
        canali_out.append({"id": canal_id, "points": points_out})

    output = {"step_m": STEP_M, "search_cap_m": SEARCH_CAP_M, "canali": canali_out}
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False)

    total_points = sum(len(c["points"]) for c in canali_out)
    print(f"Calcolata larghezza per {len(canali_out)} canali, {total_points} punti totali")


if __name__ == "__main__":
    run()
