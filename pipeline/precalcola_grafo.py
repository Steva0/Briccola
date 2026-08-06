"""
Arricchisce graph.json con dati precalcolati per accelerare il routing nell'app.

Aggiunge:
  - "c"        su ogni nodo: id della componente connessa (BFS)
  - "tip_best" su ogni nodo: [tip_idx, dist_seconds_int] del tip raggiungibile
                              piu' veloce; [-1, -1] se il nodo e' isolato
  - "spatial_index" a livello root: griglia spaziale per snap veloce

Fa parte della pipeline di build (BUILD_ALL.py lo chiama dopo gli altri script).
"""

import json, math, heapq

GRAPH_PATH    = r"C:\Users\miche\AndroidStudioProjects\OpenLagunaMaps\app\src\main\assets\graph.json"
VETTORE_PATH  = r"C:\Users\miche\AndroidStudioProjects\OpenLagunaMaps\app\src\main\assets\laguna_vettoriale.json"
DEFAULT_SPEED_KNOTS = 12.0
DEFAULT_SPEED_KMH   = DEFAULT_SPEED_KNOTS * 1.852
GRID_CELL_DEG = 0.003   # ~300m a queste latitudini


def haversine(lat1, lon1, lat2, lon2):
    r = 6371000.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat/2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon/2)**2
    return 2 * r * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def parse_speed_kmh(s):
    if s is None:
        return DEFAULT_SPEED_KMH
    import re
    m = re.search(r'[0-9]+(\.[0-9]+)?', str(s))
    return float(m.group()) * 1.852 if m else DEFAULT_SPEED_KMH


def edge_time_s(length_m, speed_kmh):
    spd = speed_kmh if speed_kmh > 0 else DEFAULT_SPEED_KMH
    return (length_m / 1000.0) / spd * 3600.0


# ------------------------------------------------------------------ #
# 1. Carica dati
# ------------------------------------------------------------------ #
print("Carico graph.json...")
with open(GRAPH_PATH, encoding='utf-8') as f:
    graph = json.load(f)

print("Carico laguna_vettoriale.json...")
with open(VETTORE_PATH, encoding='utf-8') as f:
    vettore = json.load(f)

nodes_raw = graph['nodes']        # { id: {lat, lon, ...} }
edges_raw = graph['edges']        # [ {u, v, l, d, s, ...} ]

# Mappa id -> indice per uso nel grafo
node_ids = list(nodes_raw.keys())
node_idx = {nid: i for i, nid in enumerate(node_ids)}
n_nodes  = len(node_ids)

node_lat = [nodes_raw[nid]['lat'] for nid in node_ids]
node_lon = [nodes_raw[nid]['lon'] for nid in node_ids]

# Lista adiacenza: adj[i] = list of (j, time_s)
adj = [[] for _ in range(n_nodes)]
for e in edges_raw:
    ui = node_idx.get(e['u']); vi = node_idx.get(e['v'])
    if ui is None or vi is None:
        continue
    t = edge_time_s(e['l'], parse_speed_kmh(e.get('s')))
    adj[ui].append((vi, t))
    adj[vi].append((ui, t))


# ------------------------------------------------------------------ #
# 2. Componenti connesse (BFS)
# ------------------------------------------------------------------ #
print("Calcolo componenti connesse...")
component = [-1] * n_nodes
comp_id = 0
for start in range(n_nodes):
    if component[start] != -1:
        continue
    queue = [start]
    component[start] = comp_id
    head = 0
    while head < len(queue):
        u = queue[head]; head += 1
        for v, _ in adj[u]:
            if component[v] == -1:
                component[v] = comp_id
                queue.append(v)
    comp_id += 1
print(f"  {comp_id} componenti trovate")


# ------------------------------------------------------------------ #
# 3. Carica i 6 tip (special:gate=sea_tip)
# ------------------------------------------------------------------ #
tips_latlons = []
for feat in vettore['features']:
    p = feat.get('properties', {})
    if p.get('special:gate') == 'sea_tip' and feat['geometry']['type'] == 'Point':
        c = feat['geometry']['coordinates']
        pt = (c[1], c[0])  # lat, lon
        if pt not in tips_latlons:
            tips_latlons.append(pt)
print(f"Tip caricati: {len(tips_latlons)}")


# ------------------------------------------------------------------ #
# 4. Snap di ogni tip al nodo piu' vicino del grafo
# ------------------------------------------------------------------ #
def nearest_node_to(lat, lon):
    best_i, best_d = 0, float('inf')
    for i in range(n_nodes):
        d = haversine(lat, lon, node_lat[i], node_lon[i])
        if d < best_d:
            best_d = d; best_i = i
    return best_i

tip_source_nodes = []
for lat, lon in tips_latlons:
    ni = nearest_node_to(lat, lon)
    tip_source_nodes.append(ni)
    print(f"  tip ({lat:.5f},{lon:.5f}) -> nodo {node_ids[ni]} a {haversine(lat,lon,node_lat[ni],node_lon[ni]):.0f}m")


# ------------------------------------------------------------------ #
# 5. Dijkstra da ogni tip
# ------------------------------------------------------------------ #
def dijkstra_from(source_idx):
    dist = [float('inf')] * n_nodes
    dist[source_idx] = 0.0
    pq = [(0.0, source_idx)]
    while pq:
        d, u = heapq.heappop(pq)
        if d > dist[u]:
            continue
        for v, t in adj[u]:
            nd = d + t
            if nd < dist[v]:
                dist[v] = nd
                heapq.heappush(pq, (nd, v))
    return dist

tip_dist_tables = []
for ti, src in enumerate(tip_source_nodes):
    print(f"Dijkstra dal tip {ti+1}/{len(tips_latlons)}...")
    tip_dist_tables.append(dijkstra_from(src))


# ------------------------------------------------------------------ #
# 6. Per ogni nodo: best tip (idx, dist_seconds)
# ------------------------------------------------------------------ #
print("Calcolo best tip per nodo...")
tip_best_idx  = [-1] * n_nodes
tip_best_dist = [-1] * n_nodes

for i in range(n_nodes):
    best_ti, best_d = -1, float('inf')
    for ti, dists in enumerate(tip_dist_tables):
        if dists[i] < best_d:
            best_d = dists[i]; best_ti = ti
    if best_ti >= 0 and best_d < float('inf'):
        tip_best_idx[i]  = best_ti
        tip_best_dist[i] = int(round(best_d))   # secondi interi


# ------------------------------------------------------------------ #
# 7. Spatial index (griglia)
# ------------------------------------------------------------------ #
print("Costruisco indice spaziale...")
# bounding box di tutti i nodi
min_lat = min(node_lat); max_lat = max(node_lat)
min_lon = min(node_lon); max_lon = max(node_lon)
margin = GRID_CELL_DEG
origin_lat = min_lat - margin
origin_lon = min_lon - margin
n_cols = int((max_lon + margin - origin_lon) / GRID_CELL_DEG) + 2
n_rows = int((max_lat + margin - origin_lat) / GRID_CELL_DEG) + 2

def cell_key(lat, lon):
    r = int((lat - origin_lat) / GRID_CELL_DEG)
    c = int((lon - origin_lon) / GRID_CELL_DEG)
    return r, c

# Ogni arco finisce nelle celle dei suoi due nodi estremi
cells = {}  # (row, col) -> [edge_indices]
for ei, e in enumerate(edges_raw):
    ui = node_idx.get(e['u']); vi = node_idx.get(e['v'])
    if ui is None or vi is None:
        continue
    for ni in (ui, vi):
        r, c = cell_key(node_lat[ni], node_lon[ni])
        key = f"{r},{c}"
        if key not in cells:
            cells[key] = []
        if ei not in cells[key]:
            cells[key].append(ei)

print(f"  {len(cells)} celle non vuote su {n_rows}x{n_cols}")


# ------------------------------------------------------------------ #
# 8. Arricchisce graph.json e lo salva
# ------------------------------------------------------------------ #
print("Arricchisco graph.json...")
for i, nid in enumerate(node_ids):
    nodes_raw[nid]['c']        = component[i]
    nodes_raw[nid]['tip_best'] = [tip_best_idx[i], tip_best_dist[i]]

graph['spatial_index'] = {
    'cell_deg':   GRID_CELL_DEG,
    'origin_lat': origin_lat,
    'origin_lon': origin_lon,
    'n_rows':     n_rows,
    'n_cols':     n_cols,
    'cells':      cells
}

print("Salvo graph.json...")
with open(GRAPH_PATH, 'w', encoding='utf-8') as f:
    json.dump(graph, f, separators=(',', ':'))

import os
size_mb = os.path.getsize(GRAPH_PATH) / 1024 / 1024
print(f"Fatto! graph.json ora e' {size_mb:.1f} MB")
