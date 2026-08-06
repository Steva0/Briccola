import xml.etree.ElementTree as ET
import json
import networkx as nx
import math

# Configurazione
OSM_FILE = r"D:\download\mappa\laguna_pulita.osm"
OUTPUT_GRAPH = "graph.json"

def haversine(lat1, lon1, lat2, lon2):
    R = 6371000  # Metri
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2)**2
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def build_data():
    print("Avvio conversione...")
    tree = ET.parse(OSM_FILE)
    root = tree.getroot()

    nodes = {}
    for node in root.findall('node'):
        nodes[node.get('id')] = {
            'lat': round(float(node.get('lat')), 6),
            'lon': round(float(node.get('lon')), 6),
            'tags': {tag.get('k'): tag.get('v') for tag in node.findall('tag')}
        }

    G = nx.Graph()
    no_go_areas = []

    for way in root.findall('way'):
        tags = {tag.get('k'): tag.get('v') for tag in way.findall('tag')}
        node_refs = [nd.get('ref') for nd in way.findall('nd')]
        
        if len(node_refs) < 2: continue

        if tags.get('waterway') == 'canal':
            for i in range(len(node_refs) - 1):
                u_id, v_id = node_refs[i], node_refs[i+1]
                if u_id in nodes and v_id in nodes:
                    dist = haversine(nodes[u_id]['lat'], nodes[u_id]['lon'], 
                                     nodes[v_id]['lat'], nodes[v_id]['lon'])
                    G.add_edge(u_id, v_id, 
                               length=round(dist, 2),
                               name=tags.get('name', 'Canale'),
                               maxspeed=tags.get('maxspeed'))

        elif tags.get('nav:area') == 'no_go':
            coords = [[nodes[nid]['lat'], nodes[nid]['lon']] for nid in node_refs if nid in nodes]
            no_go_areas.append({'id': way.get('id'), 'nodes': coords})

    # Output finale
    output = {
        "metadata": {
            "version": "1.0",
            "description": "Grafo LagunaNav ottimizzato"
        },
        "nodes": {},
        "edges": [],
        "no_go_areas": no_go_areas
    }

    for nid in G.nodes():
        node_data = nodes[nid]
        output["nodes"][nid] = {
            "lat": node_data["lat"],
            "lon": node_data["lon"],
            "gate": node_data['tags'].get('nav:gate')
        }

    for u, v, d in G.edges(data=True):
        output["edges"].append({
            "u": u, "v": v,
            "n": d["name"],
            "l": d["length"],
            "s": d["maxspeed"]
        })

    with open(OUTPUT_GRAPH, 'w', encoding='utf-8') as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
    
    print(f"Fatto! Grafo: {G.number_of_nodes()} nodi, {G.number_of_edges()} archi.")

if __name__ == "__main__":
    build_data()