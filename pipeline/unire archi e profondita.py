import xml.etree.ElementTree as ET
import json
import networkx as nx
import math
import rasterio
from pyproj import Transformer
import numpy as np
import os

OSM_MAIN = r"D:\download\mappa\laguna_essenziale.osm"
OSM_SPECIAL = r"D:\download\mappa\solo_tag_speciali.osm"
TIF_FILE = r"D:\download\mappa\lagoonVe_2002_GBe.tif"
OUTPUT_GRAPH = "graph.json"

transformer = Transformer.from_crs("EPSG:4326", "EPSG:3004", always_xy=True)

def haversine(lat1, lon1, lat2, lon2):
    R = 6371000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi, dlambda = math.radians(lat2-lat1), math.radians(lon2-lon1)
    a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlambda/2)**2
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def is_inside(lat, lon, poly):
    if not poly: return True
    n = len(poly)
    inside = False
    p1x, p1y = poly[0]
    for i in range(n + 1):
        p2x, p2y = poly[i % n]
        if lat > min(p1y, p2y):
            if lat <= max(p1y, p2y):
                if lon <= max(p1x, p2x):
                    if p1y != p2y:
                        xints = (lat - p1y) * (p2x - p1x) / (p2y - p1y) + p1x
                    if p1x == p2x or lon <= xints:
                        inside = not inside
        p1x, p1y = p2x, p2y
    return inside

def build_enriched_graph():
    print("Costruzione Grafo Nautico (special:)...")

    project_poly = []
    for path in [OSM_SPECIAL, OSM_MAIN]:
        if not os.path.exists(path): continue
        tree = ET.parse(path)
        root = tree.getroot()
        tn = {n.get('id'): (float(n.get('lon')), float(n.get('lat'))) for n in root.findall('node')}
        for way in root.findall('way'):
            tags = {t.get('k'): t.get('v') for t in way.findall('tag')}
            if tags.get('special:nav:boundary') == 'project':
                rs = [nd.get('ref') for nd in way.findall('nd')]
                project_poly = [tn[r] for r in rs if r in tn]
                break
        if project_poly: break

    with rasterio.open(TIF_FILE) as tif_ds:
        tif_data = tif_ds.read(1)
        nodes = {}
        G = nx.Graph()
        no_go_areas = []

        def parse(path, trust_all=False):
            if not os.path.exists(path): return
            tree = ET.parse(path)
            root = tree.getroot()
            for node in root.findall('node'):
                lon, lat = float(node.get('lon')), float(node.get('lat'))
                # trust_all=True per OSM_MAIN (laguna_essenziale.osm): spiderman.py l'ha già
                # tagliato esattamente sul confine, coi nuovi nodi creati proprio SUL bordo —
                # ri-applicare is_inside qui rischierebbe di scartarli per ambiguità
                # dell'algoritmo punto-in-poligono su un punto che giace sulla linea stessa.
                if trust_all or is_inside(lat, lon, project_poly):
                    nodes[node.get('id')] = {'lat': lat, 'lon': lon, 'gate': None}
                    for t in node.findall('tag'):
                        if t.get('k') == 'special:nav:gate': nodes[node.get('id')]['gate'] = t.get('v')

            for way in root.findall('way'):
                tags = {t.get('k'): t.get('v') for t in way.findall('tag')}
                refs = [nd.get('ref') for nd in way.findall('nd')]

                # Routing sui canali
                if tags.get('motorboat') == 'yes':
                    way_id = way.get('id')
                    for i in range(len(refs)-1):
                        u, v = refs[i], refs[i+1]
                        if u in nodes and v in nodes:
                            dist = haversine(nodes[u]['lat'], nodes[u]['lon'], nodes[v]['lat'], nodes[v]['lon'])
                            tx, ty = transformer.transform(nodes[u]['lon'], nodes[u]['lat'])
                            r, c = tif_ds.index(tx, ty)
                            d_val = abs(float(tif_data[r,c])) if (0<=r<tif_ds.height and 0<=c<tif_ds.width) else 0.5
                            G.add_edge(u, v, length=round(dist,2), depth=round(d_val,2), s=tags.get('maxspeed'), way_id=way_id)

                # Aree Ostacoli
                elif tags.get('special:nav:area') == 'no_go' or tags.get('special:nav:obstacle') == 'rock' or tags.get('special:nav:obstade') == 'rock':
                    coords = [[nodes[nid]['lat'], nodes[nid]['lon']] for nid in refs if nid in nodes]
                    if coords:
                        is_rock = tags.get('special:nav:obstacle') == 'rock' or tags.get('special:nav:obstade') == 'rock'
                        no_go_areas.append({'id': way.get('id'), 'nodes': coords, 'rock': is_rock})

        parse(OSM_MAIN, trust_all=True)
        parse(OSM_SPECIAL, trust_all=False)

        components = list(nx.connected_components(G))
        components.sort(key=len, reverse=True)
        main_comp = components[0] if components else set()

        iso_ids = set()
        edges_out = []
        for u, v, d in G.edges(data=True):
            is_iso = u not in main_comp
            if is_iso: iso_ids.add(d['way_id'])
            edges_out.append({"u": u, "v": v, "l": d["length"], "d": d["depth"], "s": d["s"], "iso": is_iso})

        with open("isolated_ways.json", "w") as f: json.dump(list(iso_ids), f)

        output = {
            "nodes": {nid: {"lat": n["lat"], "lon": n["lon"], "gate": n["gate"]} for nid, n in nodes.items() if nid in G.nodes()},
            "edges": edges_out,
            "no_go_areas": no_go_areas
        }
        with open(OUTPUT_GRAPH, "w") as f: json.dump(output, f)
        print(f"Grafo pronto. Isole: {len(components)}. Archi isolati: {len([e for e in edges_out if e['iso']])}")

if __name__ == "__main__":
    build_enriched_graph()
