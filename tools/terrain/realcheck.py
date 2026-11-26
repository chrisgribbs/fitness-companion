import json, math, statistics as st, urllib.request, urllib.parse
URL="https://spatial-img.information.qld.gov.au/arcgis/rest/services/Elevation/QldDem/ImageServer/getSamples"
def hav(a,b):
    R=6371000; p1,p2=map(math.radians,(a[0],b[0])); dl=math.radians(b[1]-a[1]); dp=p2-p1
    return 2*R*math.asin(math.sqrt(math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2))
def resample(pts,sp=10):
    out=[pts[0]]; trav=0; nxt=sp
    for a,b in zip(pts,pts[1:]):
        seg=hav(a,b)
        while seg>0 and nxt<=trav+seg:
            f=(nxt-trav)/seg; out.append((a[0]+f*(b[0]-a[0]),a[1]+f*(b[1]-a[1]))); nxt+=sp
        trav+=seg
    return out,trav
def sample(pts):
    res=[None]*len(pts)
    for s in range(0,len(pts),200):
        chunk=pts[s:s+200]
        g={"points":[[lo,la] for la,lo in chunk],"spatialReference":{"wkid":4326}}
        data=urllib.parse.urlencode({"geometry":json.dumps(g),"geometryType":"esriGeometryMultipoint","returnFirstValueOnly":"true","interpolation":"RSP_BilinearInterpolation","f":"json"}).encode()
        d=json.load(urllib.request.urlopen(urllib.request.Request(URL,data=data,headers={"User-Agent":"FitnessCompanion test"}),timeout=60))
        for x in d.get("samples",[]):
            try: res[s+x["locationId"]]=float(x["value"])
            except: pass
    return res
def hyst(v,th):
    asc=0;piv=v[0];ext=piv;tr=0
    for a in v[1:]:
        if tr==0:
            if a-piv>th: tr=1;ext=a
            elif piv-a>th: tr=-1;ext=a
        elif tr==1:
            if a>ext: ext=a
            elif ext-a>th: asc+=ext-piv;piv=ext;tr=-1;ext=a
        else:
            if a<ext: ext=a
            elif a-ext>th: piv=ext;tr=1;ext=a
    if tr==1: asc+=ext-piv
    return asc
def climb(h):
    land=[x if x is not None and x>=0.5 else None for x in h]
    known=[i for i,x in enumerate(land) if x is not None]
    f=[]
    for i,x in enumerate(land):
        if x is not None: f.append(x); continue
        p=max([k for k in known if k<i],default=None); n=min([k for k in known if k>i],default=None)
        f.append(land[p]+(land[n]-land[p])*(i-p)/(n-p) if p is not None and n is not None else land[p if p is not None else n])
    m=[st.median(f[max(0,i-3):i+4]) for i in range(len(f))]
    return hyst(m,2.0), min(m), max(m), sum(1 for x in h if x is None or x<0.5)
routes={
 "South Bank riverside (flat)":[(-27.4755,153.0195),(-27.4790,153.0225),(-27.4818,153.0255),(-27.4832,153.0275)],
 "Story Bridge crossing":[(-27.4618,153.0349),(-27.4648,153.0358),(-27.4676,153.0365),(-27.4700,153.0372)],
 "Mt Coot-tha road up to summit":[(-27.4776,152.9789),(-27.4790,152.9700),(-27.4805,152.9640),(-27.4830,152.9600),(-27.4849,152.9573)],
}
for name,pts in routes.items():
    r,L=resample(pts); h=sample(r)
    a,lo,hi,water=climb(h)
    print(f"{name}: {L/1000:.2f} km, {len(r)} samples, ground {lo:.1f}-{hi:.1f} m, water samples {water}, CLIMB {a:.1f} m")
