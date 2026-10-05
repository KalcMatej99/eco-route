'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const M=require('../app/src/main/assets/map.js');

test('idle, partial and complete place previews expose stable A and B markers',()=>{
  assert.deepEqual(M.markerSpecs({mode:'idle'}),[]);
  assert.deepEqual(M.markerSpecs({mode:'places',destination:{lat:46.2,lon:14.3,title:'Destination'}}),[
    {point:[46.2,14.3],label:'B',title:'Destination'},
  ]);
  assert.deepEqual(M.markerSpecs({mode:'places',origin:{lat:45.9,lon:13.7,label:'Origin'},destination:{lat:46.2,lon:14.3,title:'Destination'}}).map(x=>x.label),['A','B']);
});

test('ordered multi-stop places expose stable A through J markers and preserve empty slots',()=>{
  const places=Array.from({length:10},(_,index)=>({lat:45+index/10,lon:13+index/10,title:`Place ${index+1}`}));
  assert.deepEqual(M.markerSpecs({mode:'places',places}).map(x=>x.label),['A','B','C','D','E','F','G','H','I','J']);
  assert.deepEqual(M.markerSpecs({mode:'places',places:[places[0],null,places[2]]}).map(x=>x.label),['A','C']);
});

test('route markers fall back to geometry and preserve original A/B meaning on return',()=>{
  const route={coordinates:[[46.2,14.3],[45.9,13.7]]};
  assert.deepEqual(M.markerSpecs({mode:'route',route,returnLeg:true}),[
    {point:[45.9,13.7],label:'A',title:'Starting point'},
    {point:[46.2,14.3],label:'B',title:'Destination'},
  ]);
});

test('coordinates accept selected-route arrays and alternative-route objects',()=>{
  assert.deepEqual(M.coordinates([[46,14],{lat:47,lon:15},null,['x',2]]),[[46,14],[47,15]]);
});

test('safe viewport padding is asymmetric and clamps invalid inset values',()=>{
  assert.deepEqual(M.safeInsets({top:80,right:240,bottom:-2,left:NaN}),{top:80,right:240,bottom:0,left:0});
  assert.deepEqual(M.fitOptions({top:80,right:240,bottom:300,left:12}),{
    paddingTopLeft:[36,104],paddingBottomRight:[264,324],animate:false,
  });
  const constrained=M.fitOptions({top:200,right:500,bottom:600,left:100},24,{x:320,y:480});
  assert.ok(constrained.paddingTopLeft[0]+constrained.paddingBottomRight[0]<=320-96);
  assert.ok(constrained.paddingTopLeft[1]+constrained.paddingBottomRight[1]<=480-96);
});

test('fit identity changes for places and semantic route selection, not unrelated payload fields',()=>{
  const base={mode:'route',origin:{lat:46,lon:14},destination:{lat:47,lon:15},selectionKey:'outbound:r1:10'};
  assert.equal(M.fitKey(base),M.fitKey({...base,fuelPrice:1.5}));
  assert.notEqual(M.fitKey(base),M.fitKey({...base,selectionKey:'outbound:r2:10'}));
  assert.notEqual(M.fitKey(base),M.fitKey({...base,destination:{lat:48,lon:16}}));
  assert.notEqual(M.fitKey({...base,places:[base.origin,{lat:46.5,lon:14.5},base.destination]}),
    M.fitKey({...base,places:[base.origin,{lat:46.7,lon:14.7},base.destination]}));
});

test('alternative routes exclude the selected route and unusable geometry',()=>{
  const payload={route:{id:'selected'},alternatives:[
    {id:'selected',coordinates:[[1,2],[3,4]]},
    {id:'other',coordinates:[{lat:1,lon:2},{lat:3,lon:4}]},
    {id:'empty',coordinates:[]},
  ]};
  assert.deepEqual(M.alternativeRoutes(payload).map(route=>route.id),['other']);
});

test('tile state becomes loaded on any successful tile and error on exhausted or timed-out loads',()=>{
  assert.equal(M.tileOutcome(0,0),'loading');
  assert.equal(M.tileOutcome(0,2),'error');
  assert.equal(M.tileOutcome(0,0,true),'error');
  assert.equal(M.tileOutcome(1,5,true),'loaded');
});

test('speed panels use measured map pixels without viewport-height units',()=>{
  assert.equal(M.panelMaxHeight(0),320);
  assert.equal(M.panelMaxHeight(100),160);
  assert.ok(Math.abs(M.panelMaxHeight(360)-187.2)<1e-9);
  assert.equal(M.panelMaxHeight(1000),420);
});

test('full-viewport map exposes compact controls, retry, and visible OSM attribution',()=>{
  const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/map.html'),'utf8');
  assert.match(html,/html,body\{position:fixed;inset:0/);
  assert.match(html,/#map\{position:fixed;inset:0;width:auto;height:auto/);
  assert.match(html,/id="retry-tiles"/);
  assert.match(html,/id="toggle-legend"[^>]+aria-expanded="false"/);
  assert.match(html,/id="toggle-inspector"[^>]+aria-expanded="false"/);
  assert.match(html,/id="osm-attribution"[^>]+openstreetmap\.org\/copyright/);
  assert.doesNotMatch(html,/max-height:min\(52vh/);
  assert.match(html,/max-height:var\(--panel-max-height\)/);
});
