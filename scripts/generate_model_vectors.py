"""Synthetic, public model parity fixtures; no real addresses or trip captures."""
import json,sys,math
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'reference'))
import road_load_v2 as model

def encode(coords):
    previous=[0,0];output=''
    for coord in coords:
        for i,value in enumerate(coord):
            value=round(value*1e6);delta=value-previous[i];previous[i]=value
            value=~(delta<<1) if delta<0 else delta<<1
            while value>=32:output+=chr((32|(value&31))+63);value>>=5
            output+=chr(value+63)
    return output

def fixtures():
    cases=[]
    for fuel in ['diesel','petrol']:
        for grade in [0,1,-1]:
            coords=[[46+i*.001,14] for i in range(61)]
            trace={'shape':encode(coords),'elevation_interval':100,
                   'elevation':[100+grade*20*math.sin(i/6) for i in range(68)],
                   'edges':[dict(begin_shape_index=i,end_shape_index=i+20,road_class='motorway',use='road',speed=100,speed_limit=130,way_id=100+i,names=['Synthetic motorway'],end_node={'transition_time':10}) for i in [0,20,40]]}
            candidate={'id':'synthetic','label':'Synthetic motorway','trace':trace,'trip':{'summary':{'time':300}}}
            vehicle={'name':'Reference car','fuel_type':fuel,'mass_kg':1800,'drag_area_m2':.8,'rolling_resistance':.011,'tank_to_wheel_efficiency':.34 if fuel=='diesel' else .28,'auxiliary_kw':.8,'engine_overhead_l_h':.45,'air_density_kg_m3':1.225,'fuel_energy_mj_l':35.8 if fuel=='diesel' else 32}
            config={'vehicle':vehicle,'planning':{'speed_policy':'mapped_motorway_limit','segment_max_m':250,'acceleration_m_s2':.7,'deceleration_m_s2':1,'minimum_motorway_cruise_kmh':80,'speed_step_kmh':5}}
            r=model.prepare(candidate,config);base=model.evaluate(r,r['caps'],vehicle);graph=model.graph(r,config)
            profiles=[]
            for percent in [0,5,10,15]:
                deadline=base['seconds']*(1+percent/100);result=model.optimize(r,graph,deadline,config)
                profiles.append(dict(deadline=deadline,seconds=result['seconds'],liters=result['liters'],speeds=result['speeds_ms'],gap=result['optimization']['fuel_gap_liters']))
            cases.append(dict(candidate=candidate,vehicle=vehicle,segments=len(r['segments']),distance=r['distance_m'],ascent=r['ascent_m'],profiles=profiles))
    return cases
if __name__=='__main__':
    p=ROOT/'android/app/src/test/resources/model-vectors.json';p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(fixtures(),separators=(',',':'))+'\n');print(p)
