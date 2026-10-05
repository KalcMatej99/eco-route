(function (root) {
  'use strict';
  const S=root.RouteSpeed, get=id=>document.getElementById(id);
  const escape=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const speedText=value=>typeof value!=='number'||!Number.isFinite(value)?'Unavailable':`${value.toFixed(1)} km/h`;

  class SpeedMap {
    constructor(map) {
      this.map=map;
      this.cache=new Map();
      get('speed-legend-bands').innerHTML=S.bands.map(b=>`<span><i style="background:${b.color}"></i>${b.label}</span>`).join('');
      get('route-position').addEventListener('input',event=>this.inspect(Number(event.target.value),true));
      get('locate-speed').addEventListener('click',()=>{
        if(this.map&&this.shape) this.map.setView(S.pointAt(this.shape,this.position),Math.max(13,this.map.getZoom()));
      });
      get('locate-speed').disabled=!map;
      if(map) {
        this.layers=L.layerGroup().addTo(map);
        map.on('popupclose',()=>{this.pinned=false;});
        map.on('resize',()=>{if(this.hit&&root.__fitActiveRoute) root.__fitActiveRoute();});
      }
    }

    setRoute(route,plan,{eligible,fit=false}) {
      const same=this.route&&this.route.id===route.id;
      this.route=route;this.plan=plan;this.eligible=eligible;
      if(!this.cache.has(route.id)) this.cache.set(route.id,S.geometry(route.coordinates));
      this.shape=this.cache.get(route.id);
      this.position=same?Math.min(this.position,this.shape.length):this.shape.length/2;
      this.projected=null;
      get('route-position').max=String(Math.ceil(this.shape.length));
      get('route-position-end').textContent=`${route.distance_km.toFixed(1)} km`;
      get('speed-plan-kind').textContent=eligible?'Selected speed plan':'Normal model · outside time allowance';
      if(this.map) {
        this.map.closePopup();
        if(this.tooltip) this.tooltip.remove();
        this.tooltip=null;this.marker=null;this.layers.clearLayers();
        L.polyline(route.coordinates,{color:'#fff',weight:10,opacity:.9,interactive:false}).addTo(this.layers);
        this.pieceCount=0;
        route.segments.forEach((segment,i)=>{
          for(const part of S.pieces(segment,plan.speed_kmh[i],plan.speed_kmh[i+1])) {
            L.polyline(S.slice(this.shape,part.start,part.end),{
              color:part.band.color,weight:6,opacity:1,interactive:false,smoothFactor:0,
              dashArray:part.band===S.unknown?'4 5':null
            }).addTo(this.layers);
            this.pieceCount++;
          }
        });
        this.hit=L.polyline(route.coordinates,{
          color:'#000',weight:24,opacity:0,className:'speed-route-hit',smoothFactor:0,bubblingMouseEvents:false
        }).addTo(this.layers);
        this.hit.on('mousemove',event=>{if(!this.pinned) this.inspectLatLng(event.latlng,false);});
        this.hit.on('click',event=>this.inspectLatLng(event.latlng,true));
        this.hit.on('mouseout',()=>{if(this.tooltip) this.tooltip.remove();this.tooltip=null;});
        if(fit) this.map.fitBounds(this.hit.getBounds(),{padding:[35,40]});
      }
      this.inspect(this.position,false,false);
    }

    inspectLatLng(latlng,pin) {
      const zoom=this.map.getZoom();
      if(!this.projected||this.projectedZoom!==zoom) {
        this.projected=this.shape.coordinates.map(p=>this.map.project(p,zoom));
        this.projectedZoom=zoom;
      }
      const nearest=S.nearest(this.projected,this.shape.distances,this.map.project(latlng,zoom));
      this.inspect(nearest.meters,pin,true);
    }

    inspect(meters,announce=false,showTip=false) {
      if(!this.route) return;
      const point=S.inspect(this.route,this.plan,meters),segment=point.segment;
      this.position=point.meters;
      const latlng=S.pointAt(this.shape,point.meters),color=S.band(point.speed).color;
      const limit=typeof segment.limit_kmh==='number'&&segment.limit_kmh>0?`${segment.limit_kmh} km/h`:'Unknown';
      const explanation=S.explainSpeed(this.route,this.plan,meters),basis=explanation.basis;
      get('route-position').value=String(point.meters);
      get('route-position').setAttribute('aria-valuetext',`${(point.meters/1000).toFixed(2)} km from start, modeled speed ${speedText(point.speed)}`);
      get('speed-position').textContent=`${(point.meters/1000).toFixed(2)} km from start`;
      get('speed-value').textContent=point.speed===null?'—':point.speed.toFixed(1);
      get('speed-value').style.color=color;
      get('speed-road').textContent=segment.name;
      get('speed-limit').textContent=limit;
      get('speed-basis').textContent=basis;
      if(get('speed-explanation'))get('speed-explanation').textContent=explanation.reason;
      get('speed-transition').textContent=`Section ${(segment.start_m/1000).toFixed(2)}–${(segment.end_m/1000).toFixed(2)} km · ${speedText(point.v0)} → ${speedText(point.v1)}`;
      if(announce) get('speed-announcement').textContent=`${segment.name}: modeled target ${speedText(point.speed)}, ${limit==='Unknown'?'mapped speed limit unknown':'mapped limit '+limit}, ${(point.meters/1000).toFixed(2)} km from start.`;
      if(this.map) {
        if(!showTip) {
          this.map.closePopup();this.pinned=false;
          if(this.tooltip) this.tooltip.remove();this.tooltip=null;
        }
        if(!this.marker) this.marker=L.circleMarker(latlng,{radius:7,color:'#fff',weight:3,fillOpacity:1,interactive:false,pane:'markerPane'}).addTo(this.layers);
        this.marker.setLatLng(latlng).setStyle({fillColor:color});
        const content=`<div class="speed-tip"><b>${speedText(point.speed)}</b><span>Modeled target · ${(point.meters/1000).toFixed(2)} km</span>${announce?`<span>${escape(segment.name)}</span><span>Mapped limit: ${limit}</span>`:''}</div>`;
        if(showTip&&announce) {
          if(this.tooltip) this.tooltip.remove();this.tooltip=null;
          L.popup({maxWidth:240,autoPan:true,closeButton:true}).setLatLng(latlng).setContent(content).openOn(this.map);
          this.pinned=true;
        } else if(showTip) {
          if(!this.tooltip) this.tooltip=L.tooltip({direction:'top',offset:[0,-9],opacity:1,className:'route-speed-tooltip'}).setLatLng(latlng).setContent(content).addTo(this.map);
          else this.tooltip.setLatLng(latlng).setContent(content);
        }
      }
      root.__speedInspection={routeId:this.route.id,meters:point.meters,segmentIndex:point.index,
        speedKmh:point.speed,limitKmh:segment.limit_kmh,color,basis,latlng,pieceCount:this.pieceCount||0};
    }
  }
  root.SpeedMap=SpeedMap;
})(globalThis);
