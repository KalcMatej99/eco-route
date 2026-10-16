/* Shared route geometry and speed interpolation, independent of Leaflet. */
(function (root) {
  'use strict';
  const bands = [
    { min: 0, label: '<40', color: '#42328b' },
    { min: 40, label: '40–<60', color: '#3264ad' },
    { min: 60, label: '60–<80', color: '#008c9b' },
    { min: 80, label: '80–<100', color: '#268248' },
    { min: 100, label: '100–<120', color: '#b5740c' },
    { min: 120, label: '120+', color: '#b93632' }
  ];
  const unknown = { label: 'Unavailable', color: '#68717b' };
  const validSpeed = speed => typeof speed === 'number' && Number.isFinite(speed) && speed >= 0;
  function band(speed) {
    if (!validSpeed(speed)) return unknown;
    return bands.reduce((selected, next) => speed >= next.min ? next : selected, bands[0]);
  }
  function distance(a, b) {
    const rad = Math.PI / 180, p = a[0] * rad, q = b[0] * rad;
    const h = Math.sin((q-p)/2)**2 + Math.cos(p)*Math.cos(q)*Math.sin((b[1]-a[1])*rad/2)**2;
    return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1-h)));
  }
  function geometry(coordinates) {
    if (!Array.isArray(coordinates) || coordinates.length < 2) throw new Error('Route geometry is unavailable');
    const distances = [0];
    for (let i=1; i<coordinates.length; i++) distances.push(distances[i-1]+distance(coordinates[i-1],coordinates[i]));
    return {coordinates, distances, length: distances[distances.length-1]};
  }
  function upperBound(array, value) {
    let lo=0, hi=array.length;
    while(lo<hi) { const mid=(lo+hi)>>>1; if(array[mid]<=value) lo=mid+1; else hi=mid; }
    return lo;
  }
  function pointAt(shape, meters) {
    const m=Math.max(0,Math.min(shape.length,meters));
    const i=Math.max(0,Math.min(shape.coordinates.length-2,upperBound(shape.distances,m)-1));
    const f=(m-shape.distances[i])/Math.max(1e-12,shape.distances[i+1]-shape.distances[i]);
    return shape.coordinates[i].map((value,j)=>value+f*(shape.coordinates[i+1][j]-value));
  }
  function slice(shape, start, end) {
    const points=[pointAt(shape,start)];
    for(let i=upperBound(shape.distances,start);i<shape.distances.length&&shape.distances[i]<end;i++) points.push(shape.coordinates[i]);
    points.push(pointAt(shape,end));
    return points;
  }
  function speedAt(v0, v1, fraction) {
    if (!validSpeed(v0)||!validSpeed(v1)) return null;
    const f=Math.max(0,Math.min(1,fraction));
    // The Python model assumes constant acceleration within each distance section.
    return Math.sqrt(Math.max(0, v0*v0+(v1*v1-v0*v0)*f));
  }
  function inspect(route, plan, meters) {
    const m=Math.max(0,Math.min(route.distance_km*1000,meters));
    let lo=0, hi=route.segments.length-1;
    while(lo<hi) { const mid=(lo+hi)>>>1; if(route.segments[mid].end_m<=m) lo=mid+1; else hi=mid; }
    const segment=route.segments[lo], v0=plan.speed_kmh[lo], v1=plan.speed_kmh[lo+1];
    return {meters:m,index:lo,segment,v0,v1,speed:speedAt(v0,v1,(m-segment.start_m)/segment.distance_m)};
  }
  function explainSpeed(route,plan,meters) {
    const point=inspect(route,plan,meters),s=point.segment;
    if(route.ignore_speed_limits || s.ignore_speed_limits)return {basis:'Theoretical speed',reason:'Speed limits and provider speeds are ignored as calculation ceilings. Speeds are bounded by the 130 km/h model maximum and acceleration/braking assumptions.',fastest:route.baseline?inspect(route,route.baseline,meters).speed:null};
    if(!route.baseline||!s.speed_basis)return {basis:s.optimize?'Optimized motorway section':'Static road estimate',reason:'Modeled speed includes transitions between road sections.'};
    const fastest=inspect(route,route.baseline,meters).speed,cap=s.cruise_cap_kmh;
    if(point.speed<fastest-.5)return {basis:'Fuel-saving speed',reason:`Slower to save fuel for your selected arrival time. The fastest model at this point is ${fastest.toFixed(1)} km/h.`,fastest};
    if(fastest<cap-.5)return {basis:'Acceleration or braking',reason:`The speed changes around the start, finish or a slower road section. This section’s cruise ceiling is ${cap.toFixed(0)} km/h; the fastest model here is ${fastest.toFixed(1)} km/h.`,fastest};
    if(s.speed_basis==='mapped_motorway_limit')return {basis:cap<s.limit_kmh?'Model speed ceiling':'Mapped motorway limit',reason:cap<s.limit_kmh?`The model caps speeds at ${cap.toFixed(0)} km/h, below this section’s mapped limit.`:`The fastest model reaches the mapped ${s.limit_kmh} km/h limit here. The provider’s ${s.provider_speed_kmh} km/h travel estimate is not treated as the limit.`,fastest};
    return {basis:'Road-speed estimate',reason:`This road uses the provider’s ${s.provider_speed_kmh} km/h travel estimate, capped by known limits and the model maximum. ${s.limit_kmh?'A mapped limit is a ceiling, not an assumed driving speed on ramps and local roads.':s.limit_unlimited?'The provider explicitly marks the mapped limit as unlimited.':'The mapped speed limit is unknown.'}`,fastest};
  }
  function pieces(segment,v0,v1) {
    const cuts=[0,1];
    if(validSpeed(v0)&&validSpeed(v1)&&v0!==v1) {
      for(const {min} of bands.slice(1)) {
        const f=(min*min-v0*v0)/(v1*v1-v0*v0);
        if(f>0&&f<1) cuts.push(f);
      }
    }
    cuts.sort((a,b)=>a-b);
    return cuts.slice(1).map((end,i)=>({
      start:segment.start_m+cuts[i]*segment.distance_m,
      end:segment.start_m+end*segment.distance_m,
      band:band(speedAt(v0,v1,(cuts[i]+end)/2))
    }));
  }
  // Projected vertices allow hit testing without choosing the nearest vertex,
  // which would give incorrect speeds between vertices and around bends.
  function nearest(projected,distances,point) {
    let best={squaredDistance:Infinity,meters:0};
    for(let i=0;i<projected.length-1;i++) {
      const a=projected[i], b=projected[i+1], dx=b.x-a.x, dy=b.y-a.y;
      const f=Math.max(0,Math.min(1,((point.x-a.x)*dx+(point.y-a.y)*dy)/(dx*dx+dy*dy||1)));
      const d=(point.x-a.x-f*dx)**2+(point.y-a.y-f*dy)**2;
      if(d<best.squaredDistance) best={squaredDistance:d,meters:distances[i]+f*(distances[i+1]-distances[i])};
    }
    return best;
  }
  function limitText(segment) {
    return typeof segment.limit_kmh==='number'&&Number.isFinite(segment.limit_kmh)&&segment.limit_kmh>0?`${segment.limit_kmh} km/h`:segment.limit_unlimited?'Unlimited':'Unknown';
  }
  const api={limitText,bands,unknown,band,geometry,pointAt,slice,speedAt,inspect,explainSpeed,pieces,nearest};
  if(typeof module!=='undefined'&&module.exports) module.exports=api;
  else root.RouteSpeed=api;
})(globalThis);
