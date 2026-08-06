import xml.etree.ElementTree as ET

MASTER_FILE = r"D:\download\mappa\mappa laguna.osm"
UPLOAD_FILE = r"D:\download\mappa\per_upload_osm.osm"
APP_FILE = r"D:\download\mappa\laguna_per_app.osm"

# Tag che sono ESCLUSIVI della tua app e non devono andare su OSM
CUSTOM_TAG_KEYS = ['nav:area', 'nav:obstacle', 'nav:obstade', 'nav:boundary', 'nav:bypass', 'mare', 'laguna', 'gate', 'helper']

def process_osm():
    tree = ET.parse(MASTER_FILE)
    root = tree.getroot()

    # Creiamo la versione per l'APP (fondamentalmente una copia o un filtro leggero)
    # Qui teniamo tutto quello che serve al RoutingEngine
    tree.write(APP_FILE, encoding='UTF-8', xml_declaration=True)
    print(f"File per l'APP generato: {APP_FILE}")

    # Creiamo la versione per l'UPLOAD
    # 1. Rimuoviamo i tag custom dagli elementi standard
    # 2. Rimuoviamo interamente gli elementi che hanno SOLO tag custom (es. linee di confine progetto)
    
    upload_tree = ET.parse(MASTER_FILE)
    upload_root = upload_tree.getroot()
    
    elements_to_remove = []

    for parent in [upload_root]:
        for elem in list(parent):
            if elem.tag in ['way', 'node', 'relation']:
                tags = elem.findall('tag')
                tag_dict = {t.get('k'): t.get('v') for t in tags}
                
                # Identifichiamo i tag da rimuovere
                tags_to_delete = [t for t in tags if any(k in t.get('k') for k in CUSTOM_TAG_KEYS)]
                
                # Se l'elemento ha SOLO tag custom, segnamolo per l'eliminazione totale
                standard_tags = [t for t in tags if t not in tags_to_delete]
                
                if not standard_tags and tags_to_delete:
                    elements_to_remove.append(elem)
                else:
                    # Altrimenti rimuoviamo solo i tag "sporchi"
                    for t in tags_to_delete:
                        elem.remove(t)

    for elem in elements_to_remove:
        upload_root.remove(elem)

    upload_tree.write(UPLOAD_FILE, encoding='UTF-8', xml_declaration=True)
    print(f"File per UPLOAD (PULITO) generato: {UPLOAD_FILE}")

if __name__ == "__main__":
    process_osm()