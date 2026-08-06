import xml.etree.ElementTree as ET

INPUT_FILE = r"D:\download\mappa\laguna_pulita.osm"
OUTPUT_FILE = r"D:\download\mappa\canali_per_upload.osm"

# Tag standard OSM per i canali
CANAL_TAGS = ['canal', 'fairway', 'river']

def estrai_canali():
    print(f"Estrazione canali da {INPUT_FILE}...")
    tree = ET.parse(INPUT_FILE)
    root = tree.getroot()

    ways_to_keep = []
    nodes_needed = set()

    # 1. Troviamo tutte le 'way' che sono canali
    for way in root.findall('way'):
        # Saltiamo se è segnata come da eliminare
        if way.get('action') == 'delete':
            continue

        tags = {t.get('k'): t.get('v') for t in way.findall('tag')}

        # Verifichiamo se è un canale standard
        is_canal = tags.get('waterway') in CANAL_TAGS

        if is_canal:
            # Pulizia tag custom (nav:, mare, laguna) per l'upload
            for t in list(way.findall('tag')):
                k = t.get('k')
                if k.startswith('nav:') or k in ['mare', 'laguna']:
                    way.remove(t)

            ways_to_keep.append(way)
            # Salviamo gli ID dei nodi che compongono questo canale
            for nd in way.findall('nd'):
                nodes_needed.add(nd.get('ref'))

    # 2. Recuperiamo i nodi di geometria necessari
    final_nodes = []
    for node in root.findall('node'):
        if node.get('id') in nodes_needed:
            # Pulizia tag custom anche dai nodi se presenti
            for t in list(node.findall('tag')):
                if t.get('k').startswith('nav:'):
                    node.remove(t)
            final_nodes.append(node)

    # 3. Creazione del nuovo file OSM
    print(f"Trovati {len(ways_to_keep)} canali e {len(final_nodes)} nodi di geometria.")

    new_root = ET.Element('osm', version='0.6', generator='CanalExtractor')

    # I nodi devono venire prima delle way nel file OSM
    for n in final_nodes:
        new_root.append(n)
    for w in ways_to_keep:
        new_root.append(w)

    new_tree = ET.ElementTree(new_root)
    new_tree.write(OUTPUT_FILE, encoding='UTF-8', xml_declaration=True)

    print(f"Fatto! File salvato in: {OUTPUT_FILE}")

if __name__ == "__main__":
    estrai_canali()
