'use strict';
// Bounded, auditable zero-LLM fallback director. An optional LLM can only propose
// the SAME constrained goal schema; it never sends arbitrary client commands.
const crypto=require('node:crypto');
const SKILLS=new Set(['woodcutting','mining']);
const MODES=new Set(['bank','power_drop']);

function validatePolicy(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('Autopilot policy must be an object');
  if (typeof value.enabled !== 'boolean') throw Error('enabled must be boolean');
  if (!Array.isArray(value.skills) || !value.skills.length || value.skills.length>2 ||
      value.skills.some(s=>!SKILLS.has(s)) || new Set(value.skills).size!==value.skills.length)
    throw Error('skills must contain woodcutting, mining or both');
  if (!MODES.has(value.mode)) throw Error('Invalid inventory mode');
  if (!Number.isInteger(value.sessionMinutes) || value.sessionMinutes<5 || value.sessionMinutes>180)
    throw Error('Session duration must be 5–180 minutes');
  const target = value.targetLevel==null ? null : Number(value.targetLevel);
  if (target!==null && (!Number.isInteger(target)||target<1||target>99)) throw Error('Target level must be 1–99');
  return {enabled:value.enabled,skills:[...value.skills],mode:value.mode,
    sessionMinutes:value.sessionMinutes,targetLevel:target,index:0};
}
function createNextGoal(policy, previousGoal) {
  let index=policy.index || 0;
  if (previousGoal) index=(index+1)%policy.skills.length;
  const goal={id:crypto.randomUUID(),skill:policy.skills[index],mode:policy.mode,
    durationMinutes:policy.sessionMinutes,targetLevel:policy.targetLevel,
    status:'pending-script-integration',savedAt:new Date().toISOString(),source:'autopilot'};
  return {goal,policy:{...policy,index}};
}
function isCompletedForGoal(telemetry,goal) {
  if (!telemetry || !goal || typeof telemetry.state!=='string' || typeof goal.id!=='string') return false;
  if (!telemetry.state.startsWith(goal.id+'|')) return false;
  return /(?:^|[^\w])(?:MAX_TIME|MAX_LEVEL)(?:$|[^\w])/.test(telemetry.state);
}
module.exports={validatePolicy,createNextGoal,isCompletedForGoal};
