(function(root){
  'use strict';
  const finite=value=>typeof value==='number'&&Number.isFinite(value);
  function coordinates(values) {
    if(!Array.isArray(values)) return [];
    return values.map(point=>Array.isArray(point)?[point[0],point[1]]:[point&&point.lat,point&&point.lon])
      .filter(point=>finite(point[0])&&finite(point[1]));
  }
  function placePoint(place) { return place&&finite(place.lat)&&finite(place.lon)?[place.lat,place.lon]:null; }
  function markerSpecs(payload) {
    const ordered=Array.isArray(payload.places)?payload.places:[];
    if(ordered.length) return ordered.map((place,index)=>({
      point:placePoint(place),label:String.fromCharCode(65+index),title:place&&(place.title||place.label)||`Place ${index+1}`,
    })).filter(marker=>marker.point);
    const routePoints=coordinates(payload.route&&payload.route.coordinates), result=[];
    const origin=placePoint(payload.origin)||(routePoints.length?(payload.returnLeg?routePoints[routePoints.length-1]:routePoints[0]):null);
    const destination=placePoint(payload.destination)||(routePoints.length?(payload.returnLeg?routePoints[0]:routePoints[routePoints.length-1]):null);
    if(origin) result.push({point:origin,label:'A',title:payload.origin&&(payload.origin.title||payload.origin.label)||'Starting point'});
    if(destination) result.push({point:destination,label:'B',title:payload.destination&&(payload.destination.title||payload.destination.label)||'Destination'});
    return result;
  }
  function safeInsets(value) {
    const clean=key=>Math.max(0,finite(value&&value[key])?value[key]:0);
    return {top:clean('top'),right:clean('right'),bottom:clean('bottom'),left:clean('left')};
  }
  function fitOptions(value,edge=24,size=null) {
    const inset=safeInsets(value);
    if(size) {
      const fitPair=(first,second,limit)=>{
        const total=first+second;if(total<=limit||total===0)return [first,second];
        return [first*limit/total,second*limit/total];
      };
      [inset.left,inset.right]=fitPair(inset.left,inset.right,Math.max(0,size.x-96-edge*2));
      [inset.top,inset.bottom]=fitPair(inset.top,inset.bottom,Math.max(0,size.y-96-edge*2));
    }
    return {paddingTopLeft:[inset.left+edge,inset.top+edge],paddingBottomRight:[inset.right+edge,inset.bottom+edge],animate:false};
  }
  function fitKey(payload) {
    return JSON.stringify({
      mode:payload.mode||'idle',origin:placePoint(payload.origin),destination:placePoint(payload.destination),
      places:(Array.isArray(payload.places)?payload.places:[]).map(placePoint),selectionKey:payload.selectionKey||null,
    });
  }
  function alternativeRoutes(payload) {
    const selectedId=payload.route&&payload.route.id;
    return (Array.isArray(payload.alternatives)?payload.alternatives:[])
      .filter(route=>route&&route.id!==selectedId&&coordinates(route.coordinates).length>1);
  }
  function tileOutcome(successes,errors,timedOut=false) {
    if(successes>0)return 'loaded';
    if(errors>0||timedOut)return 'error';
    return 'loading';
  }
  function panelMaxHeight(mapHeight) {
    if(!finite(mapHeight)||mapHeight<=0)return 320;
    return Math.max(160,Math.min(mapHeight*.52,420));
  }
  const api={coordinates,placePoint,markerSpecs,safeInsets,fitOptions,fitKey,alternativeRoutes,tileOutcome,panelMaxHeight};
  if(typeof module!=='undefined'&&module.exports) module.exports=api;
  root.EcoMapState=api;
  if(!root.document||!root.L) return;

  const get=id=>document.getElementById(id);
  const map=L.map('map',{zoomControl:false,attributionControl:false}).setView([48.2,13.4],5);
  L.control.zoom({position:'topright'}).addTo(map);
  const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18}).addTo(map);
  const journeyContext=L.layerGroup().addTo(map), alternatives=L.layerGroup().addTo(map), markers=L.layerGroup().addTo(map), speedMap=new SpeedMap(map);
  let currentPayload={mode:'idle'}, lastPayload='', lastFitKey='', lastRouteGeometryKey='', insets=api.safeInsets(), tileSuccess=0, tileErrors=0, tileTimer=null;

  function tileState(state,message) {
    const node=get('tile-status');node.dataset.state=state;get('tile-message').textContent=message;
    node.classList.toggle('visible',state!=='loaded');
  }
  function beginTiles() {
    tileSuccess=0;tileErrors=0;clearTimeout(tileTimer);tileState('loading','Loading map…');
    tileTimer=setTimeout(()=>{if(tileOutcome(tileSuccess,tileErrors,true)==='error')tileState('error','Background map did not load. Route details remain available.');},12000);
  }
  tiles.on('loading',beginTiles);
  tiles.on('tileload',()=>{tileSuccess++;clearTimeout(tileTimer);tileState('loaded','');});
  tiles.on('tileerror',()=>{tileErrors++;});
  tiles.on('load',()=>{clearTimeout(tileTimer);if(tileOutcome(tileSuccess,tileErrors)==='loaded')tileState('loaded','');else if(tileOutcome(tileSuccess,tileErrors)==='error')tileState('error','Background map unavailable. Route details remain available.');});
  get('retry-tiles').onclick=()=>{beginTiles();tiles.redraw();};

  function routeSelect(routeId) {
    if(root.ecoRouteSelection&&typeof root.ecoRouteSelection.postMessage==='function') root.ecoRouteSelection.postMessage(routeId);
  }
  function placeIcon(label,title) {
    const accessibleTitle=String(title).replace(/[&<>"]/g,'');
    return L.divIcon({className:'',html:`<div class="place-pin" aria-label="${label}: ${accessibleTitle}">${label}</div>`,iconSize:[32,32],iconAnchor:[16,16]});
  }
  function closePanels(){['legend','inspector'].forEach(name=>{get(`${name}-panel`).classList.remove('open');get(`toggle-${name}`).setAttribute('aria-expanded','false');});}
  function clearSelectedRoute() {
    map.closePopup();if(speedMap.tooltip)speedMap.tooltip.remove();speedMap.tooltip=null;speedMap.marker=null;
    speedMap.layers.clearLayers();speedMap.route=null;speedMap.plan=null;speedMap.hit=null;speedMap.shape=null;
    get('map-tools').classList.remove('visible');closePanels();get('fit').style.display='none';
  }
  function boundsFor(payload) {
    const routePoints=(Array.isArray(payload.journeyRoutes)?payload.journeyRoutes:[])
      .flatMap(route=>coordinates(route&&route.coordinates));
    if(!routePoints.length) routePoints.push(...coordinates(payload.route&&payload.route.coordinates));
    const points=routePoints.concat(markerSpecs(payload).map(marker=>marker.point));
    return points.length?L.latLngBounds(points):null;
  }
  function fitCurrent() {
    const bounds=boundsFor(currentPayload);if(!bounds||!bounds.isValid())return;
    const size=map.getSize();if(size.x<96||size.y<96)return;
    map.fitBounds(bounds,Object.assign({maxZoom:14},fitOptions(insets,24,size)));
  }
  root.__fitActiveRoute=fitCurrent;

  function drawAlternatives(payload) {
    alternatives.clearLayers();
    alternativeRoutes(payload).forEach(route=>{
      const points=coordinates(route.coordinates);
      const visible=L.polyline(points,{color:'#626878',weight:4,opacity:.58,interactive:false}).addTo(alternatives);
      const hit=L.polyline(points,{color:'#000',weight:24,opacity:0,className:'alternative-route',bubblingMouseEvents:false}).addTo(alternatives);
      hit.on('click',()=>routeSelect(String(route.id)));hit.bindTooltip(route.label||'Alternative route',{sticky:true});visible.bringToBack();
    });
  }
  function drawJourneyContext(payload) {
    journeyContext.clearLayers();
    const active=Number.isInteger(payload.activeLegIndex)?payload.activeLegIndex:0;
    (Array.isArray(payload.journeyRoutes)?payload.journeyRoutes:[]).forEach((route,index)=>{
      if(index===active)return;
      const points=coordinates(route&&route.coordinates);if(points.length<2)return;
      L.polyline(points,{color:'#50586b',weight:5,opacity:.68,interactive:false}).addTo(journeyContext);
    });
  }
  function drawMarkers(payload) {
    markers.clearLayers();markerSpecs(payload).forEach(marker=>L.marker(marker.point,{icon:placeIcon(marker.label,marker.title),title:marker.title,keyboard:true}).addTo(markers));
  }
  function render(payload) {
    payload=payload&&typeof payload==='object'?payload:{mode:'idle'};
    const serialized=JSON.stringify(payload);if(serialized===lastPayload)return;lastPayload=serialized;currentPayload=payload;
    drawJourneyContext(payload);drawAlternatives(payload);drawMarkers(payload);
    const hasRoute=payload.mode==='route'&&payload.route&&payload.plan;
    if(hasRoute) {
      const routeGeometryKey=`${payload.route.id}:${JSON.stringify(payload.route.coordinates)}`;
      if(routeGeometryKey!==lastRouteGeometryKey)speedMap.cache.delete(payload.route.id);
      lastRouteGeometryKey=routeGeometryKey;
      speedMap.setRoute(payload.route,payload.plan,{eligible:!!payload.eligible,fit:false});
      get('map-tools').classList.add('visible');get('fit').style.display='block';
    } else clearSelectedRoute();
    const nextFitKey=fitKey(payload);
    if(nextFitKey!==lastFitKey) {
      lastFitKey=nextFitKey;
      if(payload.mode==='idle'&&!markerSpecs(payload).length) map.setView([48.2,13.4],5,{animate:false}); else fitCurrent();
    }
    setTimeout(()=>map.invalidateSize({pan:false}),0);
  }
  root.renderRoute=render;
  root.setViewportInsets=function(value) {
    const next=safeInsets(value),changed=JSON.stringify(next)!==JSON.stringify(insets);insets=next;
    const style=document.documentElement.style;style.setProperty('--safe-top',`${next.top}px`);style.setProperty('--safe-right',`${next.right}px`);style.setProperty('--safe-bottom',`${next.bottom}px`);style.setProperty('--safe-left',`${next.left}px`);
    if(changed){map.invalidateSize({pan:false});if(currentPayload.mode!=='idle')fitCurrent();}
  };
  get('fit').onclick=fitCurrent;
  function togglePanel(name){const panel=get(`${name}-panel`),open=!panel.classList.contains('open');closePanels();if(open){panel.classList.add('open');get(`toggle-${name}`).setAttribute('aria-expanded','true');}}
  get('toggle-legend').onclick=()=>togglePanel('legend');get('toggle-inspector').onclick=()=>togglePanel('inspector');
  map.on('click',event=>{if(event.originalEvent&&event.originalEvent.target&&event.originalEvent.target.closest&&event.originalEvent.target.closest('.leaflet-interactive'))return;closePanels();});
  if(root.ResizeObserver) {
    let observedSize='';
    new ResizeObserver(entries=>{
      const rect=entries[0].contentRect,next=`${Math.round(rect.width)}x${Math.round(rect.height)}`;
      document.documentElement.style.setProperty('--panel-max-height',`${panelMaxHeight(rect.height)}px`);
      if(rect.width<1||rect.height<1||next===observedSize)return;observedSize=next;
      requestAnimationFrame(()=>{map.invalidateSize({pan:false});if(currentPayload.mode!=='idle')fitCurrent();});
    }).observe(get('map'));
  }
  beginTiles();
})(typeof globalThis!=='undefined'?globalThis:this);
