import xml.etree.ElementTree as ET

INPUT_FILE = r"D:\download\mappa\laguna_pulita.osm"
OUTPUT_FILE = r"D:\download\mappa\solo_tag_speciali.osm"

# I tag che definiscono i tuoi oggetti "speciali"
SPECIAL_TAGS = ['nav:', 'mare', 'laguna', 'gate', 'helper']

def estrai_speciali():
    print(f"Estrazione tag speciali da {INPUT_FILE}...")
    tree = ET.parse(INPUT_FILE)
    root = tree.getroot()

    # Lista degli elementi da tenere
    keep_elements = []
    # Set dei nodi necessari per le way (geometria)
    nodes_needed = set()

    # Primo passaggio: Identifichiamo le way e i nodi con tag speciali
    for elem in root:
        if elem.tag in ['way', 'node', 'relation']:
            tags = {t.get('k'): t.get('v') for t in elem.findall('tag')}

            # Controlliamo se ha almeno uno dei tuoi tag speciali
            is_special = any(any(k.startswith(prefix) for prefix in SPECIAL_TAGS) for k in tags.keys())

            if is_special:
                keep_elements.append(elem)
                # Se è una way, dobbiamo salvare i riferimenti ai suoi nodi
                if elem.tag == 'way':
                    for nd in elem.findall('nd'):
                        nodes_needed.add(nd.get('ref'))

    # Secondo passaggio: recuperiamo tutti i nodi di geometria necessari per le way speciali
    # anche se quei nodi non hanno tag
    final_nodes = []
    for elem in root.findall('node'):
        if elem.get('id') in nodes_needed:
            final_nodes.append(elem)

    # Scrittura del file finale
    print(f"Trovati {len(keep_elements)} oggetti speciali e {len(final_nodes)} nodi di geometria.")

    # Creiamo un nuovo albero OSM pulito
    new_root = ET.Element('osm', version='0.6', generator='SpecialTagExtractor')

    # Aggiungiamo prima i nodi (importante per JOSM)
    for n in final_nodes:
        new_root.append(n)

    # Poi aggiungiamo le way e gli oggetti speciali
    for e in keep_elements:
        new_root.append(e)

    new_tree = ET.ElementTree(new_root)
    new_tree.write(OUTPUT_FILE, encoding='UTF-8', xml_declaration=True)

    print(f"Fatto! File salvato in: {OUTPUT_FILE}")

if __name__ == "__main__":
    estrai_speciali()
