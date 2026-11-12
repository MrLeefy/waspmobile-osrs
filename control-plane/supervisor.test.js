'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const EventEmitter=require('node:events');
const {createSupervisor,readConfig,getGoal,writeGoalForSimba}=require('./supervisor');
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'wasp-supervisor-test-'));
let assertions=0;
function check(x,y){assert.deepEqual(x,y);assertions++}
function write(relative,value){const p=path.join(dir,relative);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,typeof value==='string'?value:JSON.stringify(value));return p}
function goal(id,skill='woodcutting'){return {id,skill,mode:'bank',durationMinutes:30,targetLevel:45}}
async function run(){
  const acct={id:'worker-one',swarmDir:dir,autoLaunch:true,restartOnFailure:true,launchers:{woodcutting:{executable:process.execPath,args:['-v']},mining:{executable:process.execPath,args:['-v']}}};
  const confFile=write('host.json',{accounts:[acct]});
  check(readConfig(confFile).accounts[0].id,'worker-one');
  assert.throws(()=>readConfig(write('broken.json',{accounts:[acct,acct]})),/Duplicate/);assertions++;
  check(getGoal(acct),null);
  write('goals/worker-one.json',goal('first'));
  check(getGoal(acct).id,'first');
  writeGoalForSimba(acct,goal('first'));
  check(fs.readFileSync(path.join(dir,'goals','worker-one.goal'),'utf8').trim(),'first|woodcutting|bank|30|45');
  write('goals/worker-one.json',goal('invalid','arbitrary_shell'));
  check(getGoal(acct),null);
  write('goals/worker-one.json',goal('first'));

  const children=[];
  function fakeSpawn(executable,args,options){
    check(executable,process.execPath);
    check(options.shell,false);
    const ch=new EventEmitter();
    ch.pid=4000+children.length;
    ch.kill=()=>{ch.emit('exit',0,'SIGTERM');return true};
    children.push(ch);
    queueMicrotask(()=>ch.emit('spawn'));
    return ch;
  }
  let time=1000000;
  const sup=createSupervisor({accounts:[acct]},{spawnProcess:fakeSpawn,now:()=>time,log:()=>{}});
  sup.tick();
  check(children.length,1);
  await Promise.resolve();
  check(sup.controllers.get('worker-one').state,'process-running');
  sup.tick();
  check(children.length,1); // no duplicate
  // Explicit remote STOP prevents automatic relaunch even when restartOnFailure is set.
  write('supervisor/worker-one.stop','STOP');
  sup.tick();
  check(sup.controllers.get('worker-one').state,'stopping');
  children[0].emit('exit',0,null);
  sup.tick();
  check(sup.controllers.get('worker-one').state,'manual-stop');
  check(children.length,1);
  // Explicit remote START allows a new launch.
  write('supervisor/worker-one.start','START');
  sup.tick();
  check(children.length,2);
  check(fs.existsSync(path.join(dir,'supervisor','worker-one.stop')),false);
  // Switching a goal requests a graceful exit before launching a replacement.
  write('goals/worker-one.json',goal('second','mining'));
  sup.tick();
  check(sup.controllers.get('worker-one').state,'stopping');
  children[1].emit('exit',0,null);
  sup.tick();
  check(children.length,3);
  check(sup.controllers.get('worker-one').skill,'mining');
  // Child error is not reported as in-game login.
  children[2].emit('exit',1,null);
  check(sup.controllers.get('worker-one').state,'exited');
  time+=30001;
  sup.tick();
  check(children.length,4);
  const status=JSON.parse(fs.readFileSync(path.join(dir,'status','worker-one.json'),'utf8'));
  check(status.launchCount,4);
  console.log('PASS: '+assertions+' supervisor checks with mocked local process launch/stop/restart');
}
run().catch(err=>{console.error(err);process.exitCode=1}).finally(()=>fs.rmSync(dir,{recursive:true,force:true}));
