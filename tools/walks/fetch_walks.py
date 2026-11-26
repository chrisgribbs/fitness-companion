#!/usr/bin/env python3
"""
Builds the preset walk files (app/src/main/assets/walks/*.json) from OpenStreetMap data.

Runs on GitHub Actions (the development workspace has no internet access to these services).
For each walk:
  1. Looks up each landmark on OpenStreetMap Nominatim (1 request/second, custom User-Agent,
     limited to the Sunshine Coast area). Falls back to the hand-entered coordinates if not found.
  2. Asks the FOSSGIS walking router (OSRM, routing.openstreetmap.de, foot profile) for the real
     walking path through those landmarks, in order.
  3. Simplifies the path (Douglas–Peucker, ~4 m) and records how far along it each landmark is.
Light, one-off use in line with both services' usage policies.
Data © OpenStreetMap contributors, ODbL.
"""
import json
import re, math, os, sys, time, urllib.parse, urllib.request

UA = "FitnessCompanionPersonal/0.1 (github.com/chrisgribbs/fitness-companion; one-off route build)"
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "walks")
VIEWBOX = "152.95,-26.30,153.20,-26.90"  # lon_min,lat_max,lon_max,lat_min (Sunshine Coast)

WALKS = [
    {
        "id": "sunshine-coast-coastal-pathway",
        "name": "Sunshine Coast Coastal Pathway",
        "description": "The 73 km coastal pathway from Bells Creek in the south to Coolum in the north — "
                       "beaches, headlands, the Mooloolaba Spit and the Maroochy River.",
        "officialKm": 73.0,
        "officialSource": "Sunshine Coast Council",
        "landmarks": [
            ("Bells Creek (start)", "Bells Creek, Pelican Waters", -26.835, 153.098),
            ("Golden Beach", "Golden Beach, Queensland", -26.818, 153.120),
            ("Bulcock Beach", "Bulcock Beach, Caloundra", -26.803, 153.137),
            ("Kings Beach", "Kings Beach, Caloundra", -26.803, 153.144),
            ("Moffat Beach", "Moffat Beach, Queensland", -26.790, 153.141),
            ("Dicky Beach", "Dicky Beach, Queensland", -26.781, 153.139),
            ("Currimundi", "Currimundi, Queensland", -26.766, 153.136),
            ("Wurtulla", "Wurtulla, Queensland", -26.750, 153.131),
            ("Bokarina", "Bokarina, Queensland", -26.735, 153.129),
            ("Buddina (Kawana)", "Buddina, Queensland", -26.697, 153.136),
            ("Point Cartwright", "Point Cartwright, Queensland", -26.680, 153.139),
            ("Mooloolaba", "Mooloolaba Beach, Queensland", -26.681, 153.121),
            ("Alexandra Headland", "Alexandra Headland, Queensland", -26.671, 153.109),
            ("Maroochydore Beach", "Maroochydore Beach, Queensland", -26.652, 153.100),
            ("Picnic Point", "Picnic Point, Maroochydore", -26.645, 153.088),
            ("Maroochy River crossing", "Pacific Paradise, Queensland", -26.618, 153.076),
            ("Mudjimba", "Mudjimba Beach, Queensland", -26.616, 153.102),
            ("Marcoola", "Marcoola, Queensland", -26.588, 153.097),
            ("Mount Coolum Beach", "Mount Coolum, Queensland", -26.565, 153.094),
            ("Yaroomba", "Yaroomba, Queensland", -26.548, 153.100),
            ("Point Arkwright", "Point Arkwright, Queensland", -26.544, 153.104),
            ("Coolum Beach (finish)", "Coolum Beach, Queensland", -26.528, 153.091),
        ],
    },
    {
        "id": "noosa-coastal-walk",
        "name": "Noosa National Park Coastal Walk",
        "description": "Noosa Heads to Sunshine Beach along the headland — Boiling Pot, Tea Tree Bay, "
                       "Dolphin Point, Hells Gates and Alexandria Bay. 5.4 km one way.",
        "officialKm": 5.4,
        "officialSource": "Queensland Parks and Wildlife Service",
        "landmarks": [
            ("Noosa Heads day-use area (start)", "Noosa National Park day use area, Noosa Heads", -26.3826, 153.1053),
            ("Boiling Pot", "Boiling Pot Lookout, Noosa", -26.3817, 153.1082),
            ("Tea Tree Bay", "Tea Tree Bay, Noosa", -26.3806, 153.1112),
            ("Dolphin Point", "Dolphin Point, Noosa", -26.3792, 153.1158),
            ("Granite Bay", "Granite Bay, Noosa", -26.3806, 153.1192),
            ("Hells Gates", "Hells Gates, Noosa", -26.3835, 153.1232),
            ("Alexandria Bay (north)", "Alexandria Bay, Noosa", -26.3893, 153.1216),
            ("Alexandria Bay (south)", "Alexandria Bay, Noosa", -26.4000, 153.1172),
            ("Sunshine Beach (finish)", "Sunshine Beach, Queensland", -26.4057, 153.1122),
        ],
        "osmTrack": {"names": "Coastal (Walk|Track)", "bbox": "-26.415,153.085,-26.370,153.135",
                     "start": (-26.3826, 153.0990), "end": (-26.4057, 153.1122)},
        # Official one-way distances from the day-use area (QPWS); used to place the landmarks.
        "officialMilestonesKm": {"Boiling Pot": 0.3, "Dolphin Point": 1.2, "Hells Gates": 2.7,
                                 "Alexandria Bay (north)": 3.3, "Alexandria Bay (south)": 4.4,
                                 "Sunshine Beach (finish)": 5.4},
    },
]


def get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def haversine(a, b):
    R = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(min(1, math.sqrt(h)))


def geocode(query, lat, lon):
    url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode(
        {"q": query, "format": "jsonv2", "limit": 1, "viewbox": VIEWBOX, "bounded": 1})
    time.sleep(1.1)
    try:
        res = get_json(url)
    except Exception as e:  # noqa: BLE001
        print(f"   geocode error for {query}: {e}")
        return lat, lon, "fallback"
    if not res:
        return lat, lon, "fallback"
    glat, glon = float(res[0]["lat"]), float(res[0]["lon"])
    off = haversine((lat, lon), (glat, glon))
    if off > 600:  # suburb centroids sit inland and drag the route off the coast: keep hand-entered position
        print(f"   {query}: OSM result {off:.0f} m away, keeping hand-entered point")
        return lat, lon, f"fallback (OSM {off:.0f} m off)"
    return glat, glon, f"osm ({off:.0f} m from hand-entered)"


def route(points):
    coords = ";".join(f"{lon:.6f},{lat:.6f}" for lat, lon in points)
    last = None
    for prefix in ("routed-foot/route/v1/foot", "routed-foot/route/v1/driving"):
        url = f"https://routing.openstreetmap.de/{prefix}/{coords}?overview=full&geometries=geojson&steps=false"
        try:
            r = get_json(url)
            if r.get("code") == "Ok":
                return r["routes"][0]
            last = r
        except Exception as e:  # noqa: BLE001
            last = e
    raise RuntimeError(f"routing failed: {last}")


def overpass_track(spec):
    """Real path of a named OSM track, stitched from its ways, shortest path start→end."""
    # All paths in the area, so gaps in the named track can be bridged; the named track is strongly preferred.
    q = f'[out:json][timeout:60];way["highway"~"^(path|footway|track|steps|pedestrian|service|residential|unclassified)$"]({spec["bbox"]});out body geom;'
    ways, last = None, None
    for server in ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
                   "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"):
        try:
            req = urllib.request.Request(server, data=urllib.parse.urlencode({"data": q}).encode(), headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90) as r:
                ways = json.loads(r.read().decode())["elements"]
            print(f"   Overpass: {server}"); break
        except Exception as e:  # noqa: BLE001
            last = e; print(f"   Overpass {server} failed: {e}"); time.sleep(2)
    if ways is None:
        raise RuntimeError(f"all Overpass servers failed: {last}")
    coords, adj = {}, {}
    for w in ways:
        ids, geom = w["nodes"], w["geometry"]
        named = re.search(spec["names"], (w.get("tags") or {}).get("name", ""), re.I) is not None
        cost = 1.0 if named else 5.0
        for i, nid in enumerate(ids):
            coords[nid] = (geom[i]["lat"], geom[i]["lon"])
            if i:
                a, b = ids[i - 1], nid
                d = haversine(coords[a], coords[b]) * cost
                adj.setdefault(a, []).append((b, d)); adj.setdefault(b, []).append((a, d))
    if not coords:
        raise RuntimeError("no OSM ways found")
    near = lambda p: min(coords, key=lambda n: haversine(coords[n], p))
    s, e = near(spec["start"]), near(spec["end"])
    import heapq
    dist, prev, pq = {s: 0.0}, {}, [(0.0, s)]
    while pq:
        d, n = heapq.heappop(pq)
        if n == e: break
        if d > dist.get(n, 1e18): continue
        for m, w in adj.get(n, []):
            if d + w < dist.get(m, 1e18):
                dist[m] = d + w; prev[m] = n; heapq.heappush(pq, (d + w, m))
    if e not in dist:
        raise RuntimeError("track pieces don't connect start to end")
    path, n = [e], e
    while n != s:
        n = prev[n]; path.append(n)
    path.reverse()
    print(f"   OSM track: {len(ways)} ways, start {haversine(coords[s], spec['start']):.0f} m and end {haversine(coords[e], spec['end']):.0f} m from targets")
    line = [coords[n] for n in path]
    return line, sum(haversine(a, b) for a, b in zip(line, line[1:]))


def along(line):
    cum = [0.0]
    for a, b in zip(line, line[1:]):
        cum.append(cum[-1] + haversine(a, b))
    return cum


def point_at(line, cum, m):
    m = max(0.0, min(m, cum[-1]))
    for i in range(1, len(line)):
        if cum[i] >= m:
            f = (m - cum[i - 1]) / ((cum[i] - cum[i - 1]) or 1)
            return (line[i - 1][0] + f * (line[i][0] - line[i - 1][0]), line[i - 1][1] + f * (line[i][1] - line[i - 1][1]))
    return line[-1]


def simplify(pts, tol):
    """Douglas–Peucker on lat/lon using a local equirectangular projection (metres)."""
    if len(pts) < 3:
        return pts
    lat0 = math.radians(sum(p[0] for p in pts) / len(pts))
    xy = [(math.radians(p[1]) * math.cos(lat0) * 6371008.8, math.radians(p[0]) * 6371008.8) for p in pts]
    keep = [False] * len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = xy[a]; bx, by = xy[b]
        dx, dy = bx - ax, by - ay
        L = math.hypot(dx, dy) or 1e-9
        best, idx = 0.0, None
        for i in range(a + 1, b):
            d = abs(dy * xy[i][0] - dx * xy[i][1] + bx * ay - by * ax) / L
            if d > best:
                best, idx = d, i
        if idx is not None and best > tol:
            keep[idx] = True
            stack += [(a, idx), (idx, b)]
    return [p for p, k in zip(pts, keep) if k]


def main():
    os.makedirs(OUT, exist_ok=True)
    for w in WALKS:
        print(f"== {w['name']}")
        if "osmTrack" in w:
            try:
                line, length = overpass_track(w["osmTrack"])
                cum = along(line)
                official = w["officialMilestonesKm"]
                scale = cum[-1] / (w["officialKm"] * 1000)   # spread official distances over the measured track
                ms = [{"name": w["landmarks"][0][0], "atMeters": 0.0, "lat": round(line[0][0], 6), "lon": round(line[0][1], 6), "located": "track start"}]
                for name, km in official.items():
                    at = km * 1000 * scale
                    la, lo = point_at(line, cum, at)
                    ms.append({"name": name, "atMeters": round(at, 1), "lat": round(la, 6), "lon": round(lo, 6), "located": f"official {km} km (QPWS)"})
                path = simplify(line, 3.0)
                out = {"id": w["id"], "name": w["name"], "description": w["description"],
                       "officialKm": w["officialKm"], "officialSource": w["officialSource"],
                       "routedMeters": round(cum[-1], 1), "officialMilestonesKm": official,
                       "path": [[round(a, 6), round(b, 6)] for a, b in path], "milestones": ms,
                       "attribution": "Track © OpenStreetMap contributors (ODbL); distances: Queensland Parks and Wildlife Service"}
                with open(os.path.join(OUT, w["id"] + ".json"), "w") as f:
                    json.dump(out, f, indent=1)
                print(f"   OSM track {cum[-1]/1000:.2f} km (official {w['officialKm']} km); {len(line)} → {len(path)} points")
                for m in ms:
                    print(f"     {m['atMeters']/1000:6.2f} km  {m['name']}  {m['lat']},{m['lon']}")
                continue
            except Exception as e:  # noqa: BLE001
                print(f"   OSM track failed ({e}); falling back to routing")
        pts, notes = [], []
        for name, q, lat, lon in w["landmarks"]:
            glat, glon, how = geocode(q, lat, lon)
            print(f"   {name}: {glat:.5f},{glon:.5f}  [{how}]")
            pts.append((glat, glon)); notes.append(how)
        r = route(pts)
        line = [(c[1], c[0]) for c in r["geometry"]["coordinates"]]
        legs = [leg["distance"] for leg in r["legs"]]
        cum = [0.0]
        for d in legs:
            cum.append(cum[-1] + d)
        path = simplify(line, 4.0)
        milestones = [{"name": n[0], "atMeters": round(cum[i], 1), "lat": round(pts[i][0], 6), "lon": round(pts[i][1], 6),
                       "located": notes[i]} for i, n in enumerate(w["landmarks"])]
        out = {
            "id": w["id"], "name": w["name"], "description": w["description"],
            "officialKm": w["officialKm"], "officialSource": w["officialSource"],
            "routedMeters": round(r["distance"], 1),
            "officialMilestonesKm": w.get("officialMilestonesKm", {}),
            "path": [[round(a, 6), round(b, 6)] for a, b in path],
            "milestones": milestones,
            "attribution": "Route © OpenStreetMap contributors (ODbL), routed with OSRM via FOSSGIS",
        }
        with open(os.path.join(OUT, w["id"] + ".json"), "w") as f:
            json.dump(out, f, indent=1)
        print(f"   routed {r['distance']/1000:.2f} km (official {w['officialKm']} km); "
              f"{len(line)} → {len(path)} path points")
        for m in milestones:
            print(f"     {m['atMeters']/1000:6.2f} km  {m['name']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
