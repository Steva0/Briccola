import xml.etree.ElementTree as ET
import json
import os

OSM_MAIN = r"D:\download\mappa\laguna_essenziale.osm"
OSM_SPECIAL = r"D:\download\mappa\solo_tag_speciali.osm"
OUTPUT_GEOJSON = "laguna_vettoriale.json"

def osm_to_geojson():
    print("Generazione GeoJSON (Senza tagli automatici)...")

    iso_ids = set()
    if os.path.exists("isolated_ways.json"):
        with open("isolated_ways.json", "r") as f: iso_ids = set(json.load(f))

    all_nodes = {}
    features = []

    def process(path):
        if not os.path.exists(path): return
        tree = ET.parse(path)
        root = tree.getroot()

        # Carichiamo TUTTI i nodi senza filtrare per confine
        for n in root.findall('node'):
            node_id = n.get('id')
            lon, lat = float(n.get('lon')), float(n.get('lat'))
            all_nodes[node_id] = (lon, lat)

            tags = {t.get('k'): t.get('v') for t in n.findall('tag')}
            # Briccole e Segnali
            if 'seamark:type' in tags:
                features.append({
                    "type": "Feature",
                    "properties": {"type": "briccola", "name": tags.get('name', '')},
                    "geometry": {"type": "Point", "coordinates": [lon, lat]}
                })
            # Nodi Speciali
            elif any(k.startswith('special:') for k in tags):
                props = {k: v for k, v in tags.items() if k.startswith('special:')}
                features.append({"type": "Feature", "properties": props, "geometry": {"type": "Point", "coordinates": [lon, lat]}})

        for way in root.findall('way'):
            tags = {t.get('k'): t.get('v') for t in way.findall('tag')}
            refs = [nd.get('ref') for nd in way.findall('nd')]
            coords = [all_nodes[rid] for rid in refs if rid in all_nodes]
            if len(coords) < 2: continue

            # Teniamo i tag utili
            target_keys = {'waterway', 'motorboat', 'maxspeed', 'name', 'depth'}
            props = {k: v for k, v in tags.items() if k.startswith('special:') or k in target_keys}
            props["id"] = way.get('id')
            if way.get('id') in iso_ids: props["iso"] = True

            # Definizione tipi per rendering app
            if tags.get('motorboat') == 'yes': props["type"] = "canal"
            if tags.get('special:nav:obstacle') == 'rock' or tags.get('special:nav:obstade') == 'rock': props["type"] = "rock"

            is_poly = refs[0] == refs[-1]
            geometry = {"type": "Polygon" if is_poly else "LineString", "coordinates": [coords] if is_poly else coords}
            features.append({"type": "Feature", "properties": props, "geometry": geometry})

    process(OSM_MAIN)
    process(OSM_SPECIAL)

    with open(OUTPUT_GEOJSON, 'w', encoding='utf-8') as f:
        json.dump({"type": "FeatureCollection", "features": features}, f)
    print(f"GeoJSON pronto. Oggetti: {len(features)} (Nessun elemento tagliato)")

if __name__ == "__main__":
    osm_to_geojson()
