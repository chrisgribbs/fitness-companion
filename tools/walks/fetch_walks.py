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
import json, math, os, sys, time, urllib.parse, urllib.request

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
        # Official one-way distances from the day-use area (QPWS), shown alongside measured ones.
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
    if off > 3000:  # far from where it should be: keep hand-entered position
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
