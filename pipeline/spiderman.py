import xml.etree.ElementTree as ET
import os
from shapely.geometry import LineString, Polygon
import itertools

# CONFIGURAZIONE PERCORSI
OSM_MAIN = r"D:\download\mappa\mappa laguna.osm"
OSM_SPECIAL = r"D:\download\mappa\solo_tag_speciali.osm"
OSM_ESSENTIAL = r"D:\download\mappa\laguna_essenziale.osm"

# WHITELIST RIGOROSA
ALLOWED_WATERWAYS = {'canal', 'river'}
ALLOWED_SEAMARKS = {'pile', 'dolphin', 'mooring', 'beacon', 'buoy_lateral', 'buoy_cardinal'}
SPECIAL_PREFIX = 'special:'

def is_inside(lat, lon, poly):
    if not poly: return True # Se non c'è confine, tieni tutto
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

def clip_linestring(coords, polygon):
    """coords: lista ordinata di (lon, lat) di una way "lineare" (canale/fiume).
    Ritorna una lista di pezzi (ciascuno lista di (lon, lat)) tagliati ESATTAMENTE sul
    confine, invece di scartare/tenere l'intera way in base a "almeno un nodo dentro":
    cosi' un canale che esce dall'area di progetto si ferma proprio sul bordo (nuovo punto
    creato all'intersezione), senza perdere il pezzo restante se il nodo piu' vicino dentro
    l'area fosse lontano dal confine."""
    if len(coords) < 2:
        return []
    clipped = LineString(coords).intersection(polygon)
    if clipped.is_empty:
        return []
    geoms = list(clipped.geoms) if clipped.geom_type.startswith("Multi") else [clipped]
    return [list(g.coords) for g in geoms if g.geom_type == "LineString" and len(g.coords) >= 2]


def get_project_boundary():
    print(" -> Ricerca confine del progetto...")
    if not os.path.exists(OSM_SPECIAL): return []
    tree = ET.parse(OSM_SPECIAL)
    root = tree.getroot()

    # Mappa veloce ID -> (lon, lat) solo per questo file
    nodes = {n.get('id'): (float(n.get('lon')), float(n.get('lat'))) for n in root.findall('node')}

    for way in root.findall('way'):
        tags = {t.get('k'): t.get('v') for t in way.findall('tag') if t.get('k')}
        if tags.get('special:nav:boundary') == 'project':
            refs = [nd.get('ref') for nd in way.findall('nd')]
            return [nodes[r] for r in refs if r in nodes]
    return []

def get_refined_tags_main(elem, allowed_waterways, allowed_seamarks):
    tags = {t.get('k'): t.get('v') for t in elem.findall('tag') if t.get('k')}
    new_tags = {}
    is_canal = (tags.get('waterway') in allowed_waterways) and (tags.get('motorboat') == 'yes')
    is_seamark = tags.get('seamark:type') in allowed_seamarks

    if is_canal:
        for k in ['waterway', 'motorboat', 'maxspeed', 'name', 'depth']:
            if k in tags: new_tags[k] = tags[k]
        return new_tags
    if is_seamark:
        for k in ['seamark:type', 'name', 'depth']:
            if k in tags: new_tags[k] = tags[k]
        return new_tags
    return None

def get_refined_tags_special(elem, prefix):
    tags = {t.get('k'): t.get('v') for t in elem.findall('tag') if t.get('k')}
    new_tags = {}
    is_special = any(k.startswith(prefix) for k in tags)
    if is_special:
        for k, v in tags.items():
            if k.startswith(prefix) or k == 'depth':
                new_tags[k] = v
        return new_tags
    return None

def spiderman_osm_filter():
    print("SPIDERMAN: Avvio estrazione con ritaglio su confine...")

    boundary = get_project_boundary()
    boundary_polygon = Polygon(boundary) if len(boundary) >= 3 else None
    if boundary:
        print(f" -> Confine trovato ({len(boundary)} vertici).")
    else:
        print(" -> ATTENZIONE: Confine non trovato in solo_tag_speciali.osm!")

    nodes_to_keep = {}
    ways_to_keep = []
    nodes_all_coords = {} # Per decidere se tenere le way

    # 1. ANALISI PRELIMINARE COORDINATE NODI (Iterparse per velocità)
    for path in [OSM_MAIN, OSM_SPECIAL]:
        if not os.path.exists(path): continue
        context = ET.iterparse(path, events=('end',))
        for _, elem in context:
            if elem.tag == 'node':
                nodes_all_coords[elem.get('id')] = (float(elem.get('lon')), float(elem.get('lat')))
            elem.clear()

    # Indice inverso coordinata -> id, per riconoscere quando un punto di taglio coincide
    # con un nodo OSM gia' esistente (arrotondato a ~1cm) invece di crearne uno nuovo.
    coord_to_id = {(round(lon, 7), round(lat, 7)): nid for nid, (lon, lat) in nodes_all_coords.items()}

    # Gli id "id" di OSM devono essere interi validi (JOSM rifiuta stringhe tipo "bnd_0" con
    # "Illegal long value"): i nuovi nodi/way creati sul confine usano id NEGATIVI progressivi,
    # come fa normalmente JOSM per gli elementi non ancora caricati -- calcolati partendo sotto
    # il piu' negativo già presente nei file sorgente, per non entrare in collisione.
    existing_int_ids = [int(nid) for nid in nodes_all_coords if nid.lstrip('-').isdigit()]
    synthetic_id_seq = itertools.count(start=(min(existing_int_ids) - 1) if existing_int_ids else -1, step=-1)

    # 2. ELABORAZIONE ELEMENTI
    for path in [OSM_MAIN, OSM_SPECIAL]:
        if not os.path.exists(path): continue
        is_special_file = (path == OSM_SPECIAL)
        tree = ET.parse(path)
        root = tree.getroot()

        for elem in root.findall('node'):
            refined = get_refined_tags_special(elem, SPECIAL_PREFIX) if is_special_file else get_refined_tags_main(elem, ALLOWED_WATERWAYS, ALLOWED_SEAMARKS)
            if refined:
                lon, lat = nodes_all_coords[elem.get('id')]
                if is_inside(lat, lon, boundary):
                    for t in list(elem.findall('tag')): elem.remove(t)
                    for k, v in refined.items(): ET.SubElement(elem, 'tag', {'k': k, 'v': v})
                    nodes_to_keep[elem.get('id')] = elem

        for elem in root.findall('way'):
            refined = get_refined_tags_special(elem, SPECIAL_PREFIX) if is_special_file else get_refined_tags_main(elem, ALLOWED_WATERWAYS, ALLOWED_SEAMARKS)
            if not refined:
                continue
            refs = [nd.get('ref') for nd in elem.findall('nd')]
            points = [nodes_all_coords[r] for r in refs if r in nodes_all_coords]
            is_boundary_way = refined.get('special:nav:boundary') == 'project'
            is_closed = len(refs) >= 2 and refs[0] == refs[-1]

            if is_boundary_way or is_closed or not boundary_polygon:
                # Il confine stesso, le aree poligonali (no_go/rock/ecc.) o l'assenza di un
                # confine: comportamento invariato, si tiene l'intera way se almeno un nodo
                # e' dentro (tagliare un poligono è un problema diverso, fuori da questa richiesta).
                if any(is_inside(p[1], p[0], boundary) for p in points) or is_boundary_way:
                    for t in list(elem.findall('tag')): elem.remove(t)
                    for k, v in refined.items(): ET.SubElement(elem, 'tag', {'k': k, 'v': v})
                    ways_to_keep.append(elem)
                continue

            # Way lineare (canale/fiume): tagliata ESATTAMENTE sul confine invece di essere
            # tenuta per intero o scartata in base al singolo nodo più vicino.
            pieces = clip_linestring(points, boundary_polygon)
            for i, piece in enumerate(pieces):
                piece_refs = []
                for lon, lat in piece:
                    key = (round(lon, 7), round(lat, 7))
                    nid = coord_to_id.get(key)
                    if nid is None:
                        nid = str(next(synthetic_id_seq))
                        coord_to_id[key] = nid
                    if nid not in nodes_to_keep:
                        nodes_to_keep[nid] = ET.Element('node', {'id': nid, 'lat': str(lat), 'lon': str(lon), 'version': '1'})
                    piece_refs.append(nid)

                way_id = elem.get('id') if len(pieces) == 1 else str(next(synthetic_id_seq))
                new_way = ET.Element('way', {'id': way_id, 'version': '1'})
                for r in piece_refs:
                    ET.SubElement(new_way, 'nd', {'ref': r})
                for k, v in refined.items():
                    ET.SubElement(new_way, 'tag', {'k': k, 'v': v})
                ways_to_keep.append(new_way)

    # 3. RECUPERO NODI DI GEOMETRIA PER LE VIE RIMASTE
    nodes_needed_ids = set()
    for way in ways_to_keep:
        for nd in way.findall('nd'):
            nodes_needed_ids.add(nd.get('ref'))

    print(f" -> Recupero geometria per {len(nodes_needed_ids)} nodi rimasti...")
    for nid in nodes_needed_ids:
        if nid not in nodes_to_keep and nid in nodes_all_coords:
            lon, lat = nodes_all_coords[nid]
            nodes_to_keep[nid] = ET.Element('node', {'id': nid, 'lat': str(lat), 'lon': str(lon), 'version': '1'})

    # 4. SCRITTURA
    print(f" -> Scrittura {OSM_ESSENTIAL}...")
    new_root = ET.Element('osm', version='0.6', generator='SpidermanClipped')
    for nid in sorted(nodes_to_keep.keys(), key=int):
        new_root.append(nodes_to_keep[nid])
    for way in ways_to_keep:
        new_root.append(way)
    ET.ElementTree(new_root).write(OSM_ESSENTIAL, encoding='UTF-8', xml_declaration=True)
    print(f"COMPLETATO: {len(nodes_to_keep)} nodi e {len(ways_to_keep)} vie (Tutto il resto e' stato tagliato).")

if __name__ == "__main__":
    spiderman_osm_filter()
