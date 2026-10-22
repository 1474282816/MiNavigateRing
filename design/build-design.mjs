import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const C = { bg:'#F5F6F1', surface:'#FFFFFF', ink:'#17261E', muted:'#617168', line:'#E1E7DF', accent:'#D5F46B', green:'#246442', soft:'#E9F0E7', amber:'#A05D16', amberBg:'#FFF1DA', black:'#080B09', white:'#F4F8EE', dim:'#9BA69F' };
const paths = {
 left:'M78 20H42V4L6 40l36 36V58h36V20Z',
 right:'M6 20h36V4l36 36-36 36V58H6V20Z',
 straight:'M22 78V40H4L40 4l36 36H58v38H22Z',
 'slight-left':'M61 77V49L26 14M8 14h29M8 14v29',
 'slight-right':'M23 77V49l35-35M76 14H47M76 14v29',
 'sharp-left':'M64 77V16L20 58M20 32v26h26',
 'sharp-right':'M20 77V16l44 42M64 32v26H38',
 'keep-left':'M39 77V48L20 29V8M7 21 20 8l13 13M43 46l21-20V8',
 'keep-right':'M45 77V48l19-19V8M51 21 64 8l13 13M41 46 20 26V8',
 'uturn-left':'M64 76V28a20 20 0 0 0-40 0v30M8 43l16 16 16-16',
 'uturn-right':'M20 76V28a20 20 0 0 1 40 0v30M44 43l16 16 16-16',
 'roundabout-enter':'M42 78V64a23 23 0 1 1 23-23M53 30l12 12 12-12',
 'roundabout-exit':'M42 78V64a23 23 0 1 1 20-35l14-15M57 14h19v19',
 arrive:'M18 76V9h46v37H18M19 10l44 35M63 10 19 45',
 check:'M12 39l18 18 38-38',
 walk:'M43 13a7 7 0 1 0 0-14 7 7 0 0 0 0 14ZM32 21l12-4 11 16 15 5M43 20l-8 25 17 12 6 22M35 44 20 62 10 79M34 22 23 40 8 44',
 bike:'M24 22h14l20 31M37 22l-16 31h37l9-38H57M6 53a16 16 0 1 0 32 0 16 16 0 0 0-32 0ZM48 53a16 16 0 1 0 32 0 16 16 0 0 0-32 0',
 link:'M28 50 52 26M35 16l7-7a20 20 0 0 1 28 28l-9 9M19 34l-9 9a20 20 0 0 0 28 28l7-7',
 refresh:'M66 30A28 28 0 1 0 68 54M67 9v23H44',
 alert:'M40 7 76 70H4L40 7ZM40 28v20M40 58v2',
 band:'M29 3h22v17H29V3ZM29 64h22v17H29V64ZM23 19h34v47H23V19Z',
 home:'M8 37 40 9l32 28M19 32v39h42V32M32 71V49h16v22',
 settings:'M40 26a14 14 0 1 0 0 28 14 14 0 0 0 0-28ZM33 7h14l3 12 11 6 12-3 7 12-9 9v12l9 9-7 12-12-3-11 6-3 12H33l-3-12-11-6-12 3-7-12 9-9V43l-9-9 7-12 12 3 11-6 3-12Z'
};
const txt=(value,size=16,color='ink',weight=400,w,h,align='left')=>({type:'text',value,size,color,weight,w,h:h??Math.ceil(size*1.5),align});
const icon=(name,size=24,color='ink')=>({type:'icon',icon:name,w:size,h:size,color});
const box=(children,{w=342,h,dir='col',gap=12,pad=0,bg=null,r=0,align='start',role=null,action=null}={})=>({type:'box',children,w,h,dir,gap,pad,bg,r,align,role,action});
const space=h=>box([],{w:1,h});
const button=(label,variant='primary',action=null)=>box([txt(label,15,variant==='primary'?'ink':'green',600,294,24,'center')],{w:342,h:52,pad:14,bg:variant==='primary'?'accent':'soft',r:16,role:`Button/${variant}/${label}`,action});
const chip=(label,color='green',bg='soft',w=100)=>box([txt(label,12,color,500,w-20,20,'center')],{w,h:28,pad:4,bg,r:14,role:`Chip/${label}`});
const row=(children,w=342,gap=12,align='center')=>box(children,{w,dir:'row',gap,align});
const card=(children,{h,pad=20,bg='surface',role=null}={})=>box(children,{w:342,h,pad,gap:12,bg,r:22,role});
const rule=(mode='walk')=>row([icon(mode,20,'green'),txt(`${mode==='walk'?'步行':'骑行'} · 临近 ${mode==='walk'?'30':'100'} 米提醒`,13,'muted',400,264,22)],302);
const modes=(selected='walk')=>row(['walk','bike'].map(m=>box([icon(m,25,m===selected?'green':'muted'),txt(m==='walk'?'步行':'骑行',15,m===selected?'green':'muted',600,88,24)],{w:165,h:58,dir:'row',pad:14,gap:12,bg:m===selected?'accent':'surface',r:18,role:`Mode/${m}/${selected}`,action:`ready-${m}`})),342,12);
const device=(state='connected')=>card([row([icon('band',27,'green'),box([txt('小米手环 9 NFC',15,'ink',600,190,23),txt(state==='connected'?'已连接 · 后台同步已就绪':'正在重新连接手环',12,'muted',400,190,20)],{w:190,gap:3}),chip(state==='connected'?'已连接':'连接中',state==='connected'?'green':'amber',state==='connected'?'soft':'amberBg',61)],302,12)],{h:98,role:`Device/${state}`});
const navBar=()=>box([row([box([icon('home',21,'green'),txt('导航',11,'green',500,40,17,'center')],{w:145,gap:3,align:'center',action:'ready-walk'}),box([icon('settings',21,'muted'),txt('设置',11,'muted',400,40,17,'center')],{w:145,gap:3,align:'center',action:'settings'})],302,12),box([],{w:112,h:4,bg:'ink',r:2})],{w:390,h:80,pad:12,gap:10,bg:'surface',align:'center',role:'Navigation/Bottom'});
const statusbar=()=>row([txt('9:41',13,'ink',600,243,20),txt('5G  ▰  86%',11,'ink',500,86,20,'right')],342,13);
const appHeader=(title='MiNavigateRing',eyebrow='抬腕导航')=>row([box([icon('straight',18,'ink')],{w:32,h:32,pad:7,bg:'accent',r:11}),box([txt(title,18,'ink',600,258,27),txt(eyebrow,11,'muted',400,258,18)],{w:258,gap:1})],342,12);
const phone=(id,title,content)=>({id,title,kind:'phone',scene:box([box([statusbar(),space(8),appHeader(),space(8),...content],{w:390,h:764,pad:24,gap:10,bg:'bg'}),navBar()],{w:390,h:844,gap:0,bg:'bg',r:30})});

const phones=[];
phones.push(phone('setup','01 / 首次设置',[
 txt('抬腕，就知道\n下一步。',30,'ink',600,342,90),
 txt('完成一次设置，之后让导航留在手腕。',14,'muted',400,342,23),
 card([row([chip('已完成', 'green','soft',68),txt('手环已绑定',16,'ink',600,218,26)],302),txt('小米手环 9 NFC · 小米运动健康',13,'muted',400,302,22),row([txt('通知读取',15,'ink',600,184,26),chip('待开启','amber','amberBg',106)],302),txt('读取高德导航中的方向、距离和道路名。',13,'muted',400,302,44),button('开启通知读取','primary','ready-walk')].map(n=>n.w===342?{...n,w:302,children:n.children?.map(t=>({...t,w:t.w===294?274:t.w}))}:n),{h:264}),
 card([txt('连接与后台运行',15,'ink',600,302,26),txt('按引导允许蓝牙访问。小米运动健康\n提供后台连接，无需每次手动打开。',13,'muted',400,302,44)],{h:122}),
 txt('只在需要时引导调整后台设置。',12,'muted',400,342,20)
]));
for(const mode of ['walk','bike']) phones.push(phone(`ready-${mode}`,`0${mode==='walk'?2:3} / ${mode==='walk'?'步行':'骑行'}待出发`,[
 txt('准备好出发',28,'ink',600,342,42),txt('路线交给高德，下一步交给手环。',14,'muted',400,342,23),modes(mode),device(),
 card([row([icon('straight',28,'green'),txt('等待高德导航',18,'ink',600,258,29)],302),txt('在高德选择路线并开始导航。\n再在手环上打开「抬腕导航」。',14,'muted',400,302,48),rule(mode)],{h:192}),
 button('打开高德地图','primary',`active-${mode}`),txt('模式已记住，下次无需重新选择。',12,'muted',400,342,20)
]));
for(const mode of ['walk','bike']) phones.push(phone(`active-${mode}`,`0${mode==='walk'?4:5} / ${mode==='walk'?'步行':'骑行'}导航中`,[
 row([txt('导航进行中',27,'ink',600,218,41),chip(mode==='walk'?'步行模式':'骑行模式','green','soft',112)],342),device(),
 card([txt('下一步 · 左转',14,'green',600,302,24),row([icon('left',82,'green'),box([txt('300',62,'ink',600,192,78),txt('米后左转',14,'muted',400,192,24)],{w:192,gap:0})],302,20),txt('进入人民路',22,'ink',600,302,35),box([],{w:302,h:1,bg:'line'}),row([txt('已同步至手环',13,'green',500,180,23),txt('刚刚更新',12,'muted',400,110,22,'right')],302)],{h:306}),
 rule(mode),txt('可锁屏放进口袋，手环会继续提醒。',13,'muted',400,342,22),button('查看高德路线','secondary',`active-${mode}`)
]));
phones.push(phone('reconnecting','06 / 断连恢复',[
 txt('连接中',28,'ink',600,342,42),txt('导航仍在手机上运行。',14,'muted',400,342,23),device('reconnecting'),
 card([icon('refresh',54,'amber'),txt('正在恢复手环连接',21,'ink',600,302,34),txt('恢复后会同步最新指令。\n旧指令暂不显示，也不会触发提醒。',14,'muted',400,302,48),chip('等待连接','amber','amberBg',114)],{h:246}),
 button('检查连接','primary','settings'),txt('确认蓝牙开启，并让手环靠近手机。',13,'muted',400,342,22)
]));
phones.push(phone('unavailable','07 / 暂不可用',[
 txt('信息暂不可用',27,'ink',600,342,42),txt('手环已连接，当前指令未能识别。',14,'muted',400,342,23),device(),
 card([icon('alert',50,'amber'),txt('先查看高德路线',21,'ink',600,302,34),txt('收到可识别的导航信息后会自动恢复。\n此时不会显示猜测方向或震动提醒。',14,'muted',400,302,48)],{h:217}),button('查看高德路线','primary','active-walk')
]));
phones.push(phone('ended','08 / 导航结束',[
 txt('导航已结束',28,'ink',600,342,42),txt('本次转向提醒已停止。',14,'muted',400,342,23),device(),
 card([icon('check',62,'green'),txt('下一次，继续抬腕出发。',20,'ink',600,302,34),txt('在高德重新开始导航，\n手环会显示新的导航状态。',14,'muted',400,302,48)],{h:237}),button('返回首页','primary','ready-walk')
]));
phones.push(phone('settings','09 / 模式与设置',[
 txt('设置',28,'ink',600,342,42),txt('一次设好，之后专注前方。',14,'muted',400,342,23),
 card([txt('出行模式',16,'ink',600,302,26),txt('在首页切换步行或骑行，自动记住。',13,'muted',400,302,22),row([txt('步行临近提醒',14,'ink',400,202,25),txt('30 米',15,'green',600,88,25,'right')],302),row([txt('骑行临近提醒',14,'ink',400,202,25),txt('100 米',15,'green',600,88,25,'right')],302),txt('新转向提醒一次，普通距离更新静默。',12,'muted',400,302,20)],{h:224}),
 card([txt('连接与权限',16,'ink',600,302,26),row([txt('通知读取',14,'ink',400,202,25),txt('已开启',13,'green',500,88,25,'right')],302),row([txt('小米手环 9 NFC',14,'ink',400,202,25),txt('已连接',13,'green',500,88,25,'right')],302),txt('小米运动健康负责后台通信。',12,'muted',400,302,20)],{h:176}),
 button('查看首次设置','secondary','setup')
]));

const bands=[];
function band(id,title,children){bands.push({id,title,kind:'band',scene:box([space(21),...children],{w:192,h:490,pad:20,gap:15,bg:'black',r:96,align:'center'})});}
const bt=(s,size=18,color='white',weight=500,h)=>txt(s,size,color,weight,152,h,'center');
band('band-waiting','等待导航',[bt('抬腕导航',14,'dim',500,22),space(37),icon('straight',70,'accent'),bt('等待导航',23,'white',600,35),bt('在高德开始导航',13,'dim',400,23),space(34),bt('步行模式',12,'dim',400,20)]);
band('band-left','左转 · 普通更新',[bt('步行',13,'dim',400,21),space(4),icon('left',106,'accent'),bt('300',59,'white',600,82),bt('米后左转',18,'white',500,29),bt('人民路',20,'white',500,31),space(5),bt('已同步',11,'dim',400,18)]);
band('band-near','临近转弯 · 震动一次',[bt('即将左转',15,'accent',600,25),space(4),icon('left',106,'accent'),bt('30',64,'accent',600,84),bt('米',19,'white',500,29),bt('人民路',20,'white',500,31),space(5),bt('步行',11,'dim',400,18)]);
band('band-bike','骑行 · 临近转弯',[bt('即将右转',15,'accent',600,25),space(4),icon('right',106,'accent'),bt('100',57,'accent',600,84),bt('米',19,'white',500,29),bt('滨河路',20,'white',500,31),space(5),bt('骑行',11,'dim',400,18)]);
band('band-straight','直行 · 普通更新',[bt('骑行',13,'dim',400,21),space(4),icon('straight',106,'accent'),bt('500',59,'white',600,82),bt('米后直行',18,'white',500,29),bt('滨河路',20,'white',500,31),space(5),bt('已同步',11,'dim',400,18)]);
band('band-reconnect','连接中 · 隐藏旧指令',[bt('抬腕导航',14,'dim',500,22),space(46),icon('refresh',67,'dim'),bt('连接中',25,'white',600,39),bt('恢复后自动更新',13,'dim',400,23),space(28),bt('请靠近手机',12,'dim',400,20)]);
band('band-invalid','信息暂不可用',[bt('抬腕导航',14,'dim',500,22),space(43),icon('alert',64,'dim'),bt('信息暂不可用',18,'white',600,29),bt('请查看高德',14,'dim',400,23),space(39),bt('不发送转向提醒',11,'dim',400,20)]);
band('band-ended','导航已结束',[bt('抬腕导航',14,'dim',500,22),space(47),icon('check',66,'accent'),bt('导航已结束',21,'white',600,33),bt('转向提醒已停止',13,'dim',400,23),space(30),bt('下次出发再见',12,'dim',400,20)]);

const maneuvers=[
 {id:'left',label:'左转',instruction:'米后左转',band:'band-left'},
 {id:'right',label:'右转',instruction:'米后右转'},
 {id:'straight',label:'直行',instruction:'米后直行',band:'band-straight'},
 {id:'slight-left',label:'向左前方',instruction:'米后向左前方'},
 {id:'slight-right',label:'向右前方',instruction:'米后向右前方'},
 {id:'sharp-left',label:'向左后方',instruction:'米后向左后方'},
 {id:'sharp-right',label:'向右后方',instruction:'米后向右后方'},
 {id:'keep-left',label:'靠左行驶',instruction:'米后靠左'},
 {id:'keep-right',label:'靠右行驶',instruction:'米后靠右'},
 {id:'uturn-left',label:'左侧掉头',instruction:'米后掉头'},
 {id:'uturn-right',label:'右侧掉头',instruction:'米后掉头'},
 {id:'roundabout-enter',label:'进入环岛',instruction:'米后进入环岛'},
 {id:'roundabout-exit',label:'驶出环岛',instruction:'米后驶出环岛',exit:2},
 {id:'arrive',label:'到达目的地',instruction:'已到达'}
];
for(const m of maneuvers){
 if(!m.band){m.band='band-'+m.id;if(m.id==='arrive')band(m.band,m.label,[bt('抬腕导航',14,'dim',500,22),space(42),icon(m.id,72,'accent'),bt('已到达',26,'white',600,39),bt('目的地',18,'white',500,29),space(25),bt('本次导航完成',12,'dim',400,20)]);
 else band(m.band,m.label,[bt(m.label,14,'dim',500,23),space(4),icon(m.id,106,'accent'),bt('300',59,'white',600,82),bt(m.instruction,16,'white',500,29),bt(m.exit?'第 2 出口':'人民路',20,'white',500,31),space(5),bt('已同步',11,'dim',400,18)]);}
}

function measure(n){if(n.type!=='box')return {w:n.w,h:n.h};n.children.forEach(measure);const innerW=n.dir==='row'?n.children.reduce((a,c)=>a+c.w,0)+n.gap*Math.max(0,n.children.length-1):Math.max(0,...n.children.map(c=>c.w));const innerH=n.dir==='row'?Math.max(0,...n.children.map(c=>c.h)):n.children.reduce((a,c)=>a+c.h,0)+n.gap*Math.max(0,n.children.length-1);n.w??=innerW+2*n.pad;n.h??=innerH+2*n.pad;return n;}
for(const screen of [...phones,...bands])measure(screen.scene);
const violations=[];
function bounds(n,where){if(n.type!=='box')return;const usableW=n.w-2*n.pad,usableH=n.h-2*n.pad;const total=n.children.reduce((a,c)=>a+(n.dir==='row'?c.w:c.h),0)+n.gap*Math.max(0,n.children.length-1);if(n.children.length&&total>(n.dir==='row'?usableW:usableH)+.1)violations.push(`${where}: ${n.dir} content ${total} exceeds ${n.dir==='row'?usableW:usableH}`);for(const [i,c]of n.children.entries()){if((n.dir==='row'?c.h:c.w)>(n.dir==='row'?usableH:usableW)+.1)violations.push(`${where}/${i}: cross-axis overflow`);bounds(c,`${where}/${i}`);}}
for(const s of [...phones,...bands])bounds(s.scene,s.id);
if(violations.length)throw new Error(violations.join('\n'));
const data={name:'MiNavigateRing',version:'1.1',created:'2026-10-12',colors:C,paths,phones,bands,maneuvers};
fs.writeFileSync(path.join(root,'design-data.json'),JSON.stringify(data,null,2));
const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
function paint(n,x=0,y=0){let out='';if(n.type==='box'){if(n.bg)out+=`<rect x="${x}" y="${y}" width="${n.w}" height="${n.h}" rx="${n.r}" fill="${C[n.bg]}"/>`;let cursor=n.pad;for(const c of n.children){const cross=n.align==='center'?(n.dir==='row'?(n.h-c.h)/2:(n.w-c.w)/2):n.pad;out+=paint(c,x+(n.dir==='row'?cursor:cross),y+(n.dir==='row'?cross:cursor));cursor+=(n.dir==='row'?c.w:c.h)+n.gap;}}else if(n.type==='text'){const tx=x+(n.align==='center'?n.w/2:n.align==='right'?n.w:0);const anchor=n.align==='center'?'middle':n.align==='right'?'end':'start';out+=`<text x="${tx}" y="${y+n.size*1.17}" font-family="Inter, 'Noto Sans SC', 'Microsoft YaHei', sans-serif" font-size="${n.size}" font-weight="${n.weight}" fill="${C[n.color]}" text-anchor="${anchor}">${n.value.split('\n').map((l,i)=>`<tspan x="${tx}" dy="${i?n.size*1.55:0}">${esc(l)}</tspan>`).join('')}</text>`;}else{const solid=['left','right','straight'].includes(n.icon);out+=`<svg x="${x}" y="${y}" width="${n.w}" height="${n.h}" viewBox="0 0 84 84"><path d="${paths[n.icon]}" fill="${solid?C[n.color]:'none'}" stroke="${solid?'none':C[n.color]}" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"/></svg>`;}return out;}
function svg(s){return `<svg xmlns="http://www.w3.org/2000/svg" width="${s.scene.w}" height="${s.scene.h}" viewBox="0 0 ${s.scene.w} ${s.scene.h}">${paint(s.scene)}</svg>`;}
fs.mkdirSync(path.join(root,'svg'),{recursive:true});for(const s of [...phones,...bands])fs.writeFileSync(path.join(root,'svg',s.id+'.svg'),svg(s));
for(const m of maneuvers)fs.writeFileSync(path.join(root,'svg','icon-'+m.id+'.svg'),`<svg xmlns="http://www.w3.org/2000/svg" width="84" height="84" viewBox="0 0 84 84">${paint(icon(m.id,84,'green'))}</svg>`);
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MiNavigateRing · 界面设计</title><style>
*{box-sizing:border-box}body{margin:0;color:#17261e;background:#ecefe7;font-family:Inter,'Microsoft YaHei',sans-serif}button,a{font:inherit}header{height:86px;padding:0 5%;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #d7ded3}header strong{font-size:19px}header small{color:#617168;letter-spacing:1px}.brand{display:flex;gap:12px;align-items:center}.brand-mark{background:#d5f46b;border-radius:12px;width:34px;height:34px;display:grid;place-items:center;font-size:24px}main{max-width:1540px;margin:auto;padding:48px 56px}.hero{display:grid;grid-template-columns:1fr 1.2fr;gap:60px;align-items:center}.eyebrow{font-size:12px;font-weight:700;letter-spacing:3px;color:#246442}.hero h1{font-size:64px;line-height:1.13;letter-spacing:-3px;font-weight:600;margin:22px 0}.hero h1 span{color:#246442}.hero p{line-height:1.9;color:#617168;font-size:15px;max-width:445px}.pill{display:inline-block;padding:9px 13px;background:#e1e8dc;border-radius:30px;font-size:12px;margin:4px 5px 4px 0}.controls{display:flex;flex-wrap:wrap;gap:8px;margin-top:25px}.controls button{padding:10px 16px;border:1px solid #cbd4c6;border-radius:12px;background:transparent;cursor:pointer;color:#617168}.controls button.selected{background:#17261e;color:#d5f46b;border-color:#17261e}.stage{display:flex;gap:37px;align-items:center;justify-content:center;background:#e2e7dc;border-radius:36px;padding:28px}.phone-shell{background:#121712;border:6px solid #121712;border-radius:37px;width:324px;box-shadow:0 24px 45px #17261e20;overflow:hidden}.phone-shell svg{display:block;width:312px;height:auto}.band-shell{width:154px;border:6px solid #191e1a;border-radius:86px;background:#080b09;box-shadow:0 18px 28px #17261e25;overflow:hidden}.band-shell svg{display:block;width:142px;height:auto}.stage-caption{text-align:center;font-size:12px;color:#617168;margin:17px 0 0}.section-head{margin:68px 0 24px;display:flex;justify-content:space-between;align-items:end}.section-head h2{font-size:26px;font-weight:600;margin:7px 0}.section-head p{font-size:13px;color:#617168;margin:0}.phones{display:grid;grid-template-columns:repeat(4,1fr);gap:26px}.screen-tile{min-width:0}.screen-tile h3{font-size:13px;font-weight:500;margin:0 0 14px}.screen-tile svg{display:block;width:100%;height:auto;border-radius:23px;box-shadow:0 7px 22px #17261e0c}.bands{display:grid;grid-template-columns:repeat(8,1fr);gap:18px;padding:25px;border-radius:25px;background:#e2e7dc}.bands .screen-tile h3{font-size:11px;min-height:32px;line-height:1.6}.bands .screen-tile svg{border-radius:100px}.notes{display:grid;grid-template-columns:repeat(3,1fr);gap:20px;margin-top:32px}.note{border-top:1px solid #cbd4c6;padding-top:18px}.note strong{font-size:15px}.note p{font-size:13px;line-height:1.8;color:#617168}.foot{margin-top:55px;padding:24px 0;border-top:1px solid #cbd4c6;color:#617168;font-size:12px;line-height:1.8}a{color:#246442}@media(max-width:1050px){main{padding:32px 24px}.hero{grid-template-columns:1fr;gap:28px}.hero h1{font-size:49px}.phones{grid-template-columns:repeat(3,1fr)}.bands{grid-template-columns:repeat(4,1fr)}}@media(max-width:600px){header{padding:0 20px}header small{display:none}.phones{grid-template-columns:repeat(2,1fr)}.bands{grid-template-columns:repeat(2,1fr)}.notes{grid-template-columns:1fr}.stage{gap:16px;padding:18px}.phone-shell{width:239px}.phone-shell svg{width:227px}.band-shell{width:105px}.band-shell svg{width:93px}.hero h1{font-size:43px}}
</style><header><div class="brand"><div class="brand-mark">↗</div><strong>MiNavigateRing</strong></div><small>ANDROID + XIAOMI BAND / DESIGN V1.0</small></header><main><section class="hero"><div><div class="eyebrow">抬腕导航 · 首版界面方案</div><h1>路线在手机，<br><span>下一步在手腕。</span></h1><p>高德步行与骑行导航，转成抬腕就能读懂的大箭头与距离。手机可以锁屏，手环只在需要时提醒。</p><div><span class="pill">高德地图</span><span class="pill">步行 30 米</span><span class="pill">骑行 100 米</span><span class="pill">手环 9 NFC</span></div><div class="controls" id="modes"><button data-mode="walk" class="selected">步行</button><button data-mode="bike">骑行</button></div><div class="controls" id="states"><button data-state="active" class="selected">导航中</button><button data-state="near">临近转弯</button><button data-state="reconnect">连接中</button><button data-state="invalid">暂不可用</button><button data-state="ended">已结束</button></div><p style="font-size:12px;margin-top:22px">示例导航数据用于展示设计，尚未连接实机。<br>点击状态和模式，可查看手机与手环的对应页面。</p></div><div><div class="stage"><div class="phone-shell" id="hero-phone"></div><div class="band-shell" id="hero-band"></div></div><div class="stage-caption">手机负责准备与状态，手环负责下一步。</div></div></section><div class="section-head"><div><div class="eyebrow">01 / PHONE</div><h2>少设置，顺手出发。</h2></div><p>390 × 844 · 首次设置、日常导航与异常恢复</p></div><section class="phones">${phones.map(s=>`<article class="screen-tile"><h3>${s.title}</h3>${svg(s)}</article>`).join('')}</section><div class="section-head"><div><div class="eyebrow">02 / BAND</div><h2>抬腕时，只看下一步。</h2></div><p>192 × 490 · 真实屏幕比例</p></div><section class="bands">${bands.map(s=>`<article class="screen-tile"><h3>${s.title}</h3>${svg(s)}</article>`).join('')}</section><section class="notes"><div class="note"><strong>信息层级</strong><p>方向最大，距离其次，道路名明确。暂不堆叠总距离、到达时间与地图。</p></div><div class="note"><strong>有节制的提醒</strong><p>新转向提醒一次，临近再提醒一次。普通距离更新静默，过近提醒合并。</p></div><div class="note"><strong>失效信息不伪装成当前指令</strong><p>断连或解析失败时移除旧箭头与距离，明确显示状态；恢复后同步最新指令。</p></div></section><div class="foot">设计依据：PROJECT_SPEC.md · 2026-10-12。熄屏更新、切回表盘后的震动及后台同步均为待实机验证的产品目标。</div></main><script>const P=${JSON.stringify(Object.fromEntries(phones.map(s=>[s.id,svg(s)])))};const B=${JSON.stringify(Object.fromEntries(bands.map(s=>[s.id,svg(s)])))};let mode='walk',state='active';function update(){const phone=state==='reconnect'?'reconnecting':state==='invalid'?'unavailable':state==='ended'?'ended':'active-'+mode;const band=state==='reconnect'?'band-reconnect':state==='invalid'?'band-invalid':state==='ended'?'band-ended':state==='near'?(mode==='walk'?'band-near':'band-bike'):'band-left';document.getElementById('hero-phone').innerHTML=P[phone];document.getElementById('hero-band').innerHTML=B[band].replace(mode==='bike'&&band==='band-left'?'步行':'___',mode==='bike'?'骑行':'___');document.querySelectorAll('[data-mode]').forEach(b=>b.classList.toggle('selected',b.dataset.mode===mode));document.querySelectorAll('[data-state]').forEach(b=>b.classList.toggle('selected',b.dataset.state===state));}document.querySelectorAll('[data-mode]').forEach(b=>b.onclick=()=>{mode=b.dataset.mode;update()});document.querySelectorAll('[data-state]').forEach(b=>b.onclick=()=>{state=b.dataset.state;update()});update();</script></html>`;
const iconsSection=`<section id="navigation-icons"><div class="section-head"><div><div class="eyebrow">03 / NAVIGATION ICONS</div><h2>每一种转向，都有清晰的指示。</h2></div><p>14 个矢量图标 · 点击查看手机与手环效果</p></div><div class="icon-grid">${maneuvers.map(m=>`<button class="icon-tile" data-maneuver="${m.id}" aria-label="预览${m.label}"><svg width="68" height="68" viewBox="0 0 84 84">${paint(icon(m.id,84,'green'))}</svg><strong>${m.label}</strong><small>${m.exit?'已知出口编号时显示；示例为第 2 出口':m.id==='arrive'?'到达与手动结束分开显示':'独立矢量 · 手环大图标'}</small></button>`).join('')}</div><p class="icon-note">图标形状、手环页面和消息语义分别定义。环岛出口编号只有在源数据明确提供时显示，不能推测；未知方向显示“信息暂不可用”。步行和骑行各自可识别的动作范围，仍需高德真实通知样本验证。</p></section>`;
const iconSheet=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MiNavigateRing · 导航图标</title><style>*{box-sizing:border-box}body{margin:0;padding:22px;background:#f5f6f1;font-family:'Microsoft YaHei',sans-serif;color:#17261e}header{display:flex;justify-content:space-between;align-items:center;margin-bottom:19px}h1{font-size:23px;margin:0}header span{font-size:12px;color:#617168}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.tile{border:1px solid #dce3d6;border-radius:16px;background:#fff;min-height:108px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;font-size:13px}.tile svg{display:block;width:52px;height:52px}p{font-size:12px;color:#617168;line-height:1.8;margin:17px 0 0}a{color:#246442}</style><header><h1>导航图标 · 14 种动作</h1><span>MiNavigateRing / V1.1</span></header><div class="grid">${maneuvers.map(m=>`<div class="tile"><svg viewBox="0 0 84 84">${paint(icon(m.id,84,'green'))}</svg><span>${m.label}</span></div>`).join('')}</div><p>可识别范围以高德实测样本为准；未知方向不默认直行。<br><a href="index.html#navigation-icons">打开完整预览，点击图标查看手机与手环效果 →</a></p></html>`;
fs.writeFileSync(path.join(root,'icons.html'),iconSheet);
const css=`<style>.icon-grid{display:grid;grid-template-columns:repeat(7,1fr);gap:14px}.icon-tile{display:flex;flex-direction:column;align-items:center;gap:12px;min-height:188px;padding:23px 10px;border:1px solid #d7ded3;border-radius:20px;background:#f5f6f1;color:#17261e;cursor:pointer}.icon-tile:hover,.icon-tile.selected{border-color:#246442;background:#e3edda}.icon-tile strong{font-size:14px}.icon-tile small{font-size:10px;line-height:1.6;color:#617168;text-align:center}.icon-note{font-size:12px;line-height:1.8;color:#617168;margin-top:18px}@media(max-width:1050px){.icon-grid{grid-template-columns:repeat(4,1fr)}}@media(max-width:600px){.icon-grid{grid-template-columns:repeat(2,1fr)}}</style>`;
const interaction=`const P=${JSON.stringify(Object.fromEntries(phones.map(s=>[s.id,svg(s)])))};const B=${JSON.stringify(Object.fromEntries(bands.map(s=>[s.id,svg(s)])))};const M=${JSON.stringify(maneuvers)};const PATHS=${JSON.stringify(paths)};let mode='walk',state='active',maneuver='left';function update(){const m=M.find(x=>x.id===maneuver);const phone=state==='reconnect'?'reconnecting':state==='invalid'?'unavailable':state==='ended'||m.id==='arrive'?'ended':'active-'+mode;let p=P[phone],b;if(state==='reconnect')b=B['band-reconnect'];else if(state==='invalid')b=B['band-invalid'];else if(state==='ended')b=B['band-ended'];else if(m.id==='arrive'){b=B[m.band];p=p.replace('导航已结束','已到达目的地');}else{const solid=['left','right','straight'].includes(m.id);const vector='<path d="'+PATHS[m.id]+'" fill="'+(solid?'#246442':'none')+'" stroke="'+(solid?'none':'#246442')+'" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"/>';p=p.replace(/<path d="M78 20H42V4L6 40l36 36V58h36V20Z"[^>]*>/,vector).replace('下一步 · 左转','下一步 · '+m.label).replace('米后左转',m.instruction);b=B[m.band].replace('>500</tspan>','>300</tspan>').replace('滨河路','人民路').replace('>骑行</tspan>','>'+ (mode==='walk'?'步行':'骑行')+'</tspan>');if(m.exit)p=p.replace('进入人民路','第 '+m.exit+' 出口');if(state==='near'){const distance=mode==='walk'?'30':'100';p=p.replace('>300</tspan>','>'+distance+'</tspan>');b=b.replace('>300</tspan>','>'+distance+'</tspan>');}if(mode==='bike')b=b.replace('>步行</tspan>','>骑行</tspan>');}document.getElementById('hero-phone').innerHTML=p;document.getElementById('hero-band').innerHTML=b;document.querySelectorAll('[data-mode]').forEach(x=>x.classList.toggle('selected',x.dataset.mode===mode));document.querySelectorAll('[data-state]').forEach(x=>x.classList.toggle('selected',x.dataset.state===state));document.querySelectorAll('[data-maneuver]').forEach(x=>x.classList.toggle('selected',x.dataset.maneuver===maneuver));}document.querySelectorAll('[data-mode]').forEach(x=>x.onclick=()=>{mode=x.dataset.mode;update()});document.querySelectorAll('[data-state]').forEach(x=>x.onclick=()=>{state=x.dataset.state;update()});document.querySelectorAll('[data-maneuver]').forEach(x=>x.onclick=()=>{maneuver=x.dataset.maneuver;state='active';update();document.querySelector('.stage').scrollIntoView({behavior:'smooth',block:'center'});});update();`;
const previewHtml = html.replace('</style>', '</style>'+css).replace('<section class="notes">',iconsSection+'<section class="notes">').replace(/<script>[\s\S]*<\/script><\/html>$/,`<script>${interaction}</script></html>`).replace('DESIGN V1.0','DESIGN V1.1');
fs.writeFileSync(path.join(root,'index.html'),previewHtml);
console.log(JSON.stringify({phones:phones.length,bands:bands.length,maneuvers:maneuvers.length,layoutViolations:violations.length,preview:path.join(root,'index.html')},null,2));
