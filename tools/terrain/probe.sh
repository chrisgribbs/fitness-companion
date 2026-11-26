#!/usr/bin/env bash
# Checks the terrain services the app uses for climb, at known Brisbane spots.
set -u
PTS='[[152.9564,-27.4847],[153.0302,-27.4783],[153.0335,-27.4790],[153.0262,-27.4698],[153.0870,-26.3940]]'
echo "== Queensland DEM getSamples (Mt Coot-tha summit, river mid, Kangaroo Pt cliff top, CBD, Noosa)"
curl -s -X POST "https://spatial-img.information.qld.gov.au/arcgis/rest/services/Elevation/QldDem/ImageServer/getSamples" \
  --data-urlencode "geometry={\"points\":$PTS,\"spatialReference\":{\"wkid\":4326}}" \
  --data-urlencode "geometryType=esriGeometryMultipoint" \
  --data-urlencode "returnFirstValueOnly=true" \
  --data-urlencode "interpolation=RSP_BilinearInterpolation" \
  --data-urlencode "f=json" | head -c 3000; echo
echo "== Open-Meteo (Copernicus 90 m)"
curl -s "https://api.open-meteo.com/v1/elevation?latitude=-27.4847,-27.4783,-27.4790,-27.4698,-26.3940&longitude=152.9564,153.0302,153.0335,153.0262,153.0870"; echo
echo "== timing for 500 points"
P=$(python3 -c "import json;print(json.dumps([[153.0+i*0.0001,-27.48] for i in range(500)]))")
time curl -s -o /tmp/o.json -w "%{http_code} %{size_download}\n" -X POST "https://spatial-img.information.qld.gov.au/arcgis/rest/services/Elevation/QldDem/ImageServer/getSamples" \
  --data-urlencode "geometry={\"points\":$P,\"spatialReference\":{\"wkid\":4326}}" --data-urlencode "geometryType=esriGeometryMultipoint" \
  --data-urlencode "returnFirstValueOnly=true" --data-urlencode "f=json"
python3 -c "import json;d=json.load(open('/tmp/o.json'));s=d.get('samples',[]);print(len(s),'samples; first',s[:2] if s else d)"
