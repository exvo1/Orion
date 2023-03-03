/*
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * http://www.gnu.org/copyleft/gpl.html
 */
package l2jorion.game.ai;

import static l2jorion.game.ai.CtrlIntention.AI_INTENTION_ATTACK;
import static l2jorion.game.ai.CtrlIntention.AI_INTENTION_FOLLOW;
import static l2jorion.game.ai.CtrlIntention.AI_INTENTION_IDLE;

import java.util.concurrent.Future;

import l2jorion.Config;
import l2jorion.game.controllers.GameTimeController;
import l2jorion.game.geo.GeoData;
import l2jorion.game.model.L2Character;
import l2jorion.game.model.L2Object;
import l2jorion.game.model.L2Skill;
import l2jorion.game.model.L2Summon;
import l2jorion.game.model.Location;
import l2jorion.game.model.actor.instance.L2MonsterInstance;
import l2jorion.game.model.actor.instance.L2PcInstance;
import l2jorion.game.network.serverpackets.ActionFailed;
import l2jorion.game.network.serverpackets.AutoAttackStart;
import l2jorion.game.network.serverpackets.AutoAttackStop;
import l2jorion.game.network.serverpackets.CharMoveToLocation;
import l2jorion.game.network.serverpackets.Die;
import l2jorion.game.network.serverpackets.MoveToPawn;
import l2jorion.game.network.serverpackets.StopMove;
import l2jorion.game.network.serverpackets.StopRotation;
import l2jorion.game.taskmanager.AttackStanceTaskManager;
import l2jorion.game.thread.ThreadPoolManager;
import l2jorion.game.util.Util;
import l2jorion.logger.Logger;
import l2jorion.logger.LoggerFactory;

abstract class AbstractAI implements Ctrl
{
	protected static final Logger LOG = LoggerFactory.getLogger(AbstractAI.class);
	
	protected Future<?> _followTask = null;
	private static final int FOLLOW_INTERVAL = 1000;
	private static final int ATTACK_FOLLOW_INTERVAL = 500;
	
	private class FollowTask implements Runnable
	{
		protected int _range = 70;
		
		public FollowTask()
		{
		}
		
		public FollowTask(int range)
		{
			_range = range;
		}
		
		@Override
		public void run()
		{
			try
			{
				if (_followTask == null)
				{
					return;
				}
				
				L2Character followTarget = getFollowTarget();
				if (followTarget == null)
				{
					if (_actor instanceof L2Summon)
					{
						((L2Summon) _actor).setFollowStatus(false);
					}
					
					setIntention(AI_INTENTION_IDLE);
					return;
				}
				
				if (!(_actor instanceof L2Summon) && _actor.getTarget() == null)
				{
					stopFollow();
					return;
				}
				
				// if (!_actor.isInsideRadius(followTarget, _range, true, false))
				if (!Util.checkIfInRange(_range, _actor, followTarget, true))
				{
					// if (!_actor.isInsideRadius(followTarget, 15000, true, false))
					if (!Util.checkIfInRange(15000, _actor, followTarget, true))
					{
						if (_actor instanceof L2Summon)
						{
							((L2Summon) _actor).setFollowStatus(false);
						}
						setIntention(AI_INTENTION_IDLE);
						return;
					}
					
					if (_actor instanceof L2MonsterInstance)
					{
						if (_actor.isAllSkillsDisabled() || _actor.isCastingNow())
						{
							clientStopMoving(null);
							return;
						}
					}
					
					moveToPawn(followTarget, _range);
				}
			}
			catch (Throwable t)
			{
				if (Config.ENABLE_ALL_EXCEPTIONS)
				{
					t.printStackTrace();
				}
			}
		}
	}
	
	protected final L2Character _actor;
	protected CtrlIntention _intention = AI_INTENTION_IDLE;
	protected Object _intentionArg0 = null;
	protected Object _intentionArg1 = null;
	
	protected boolean _clientMoving;
	protected boolean _clientAutoAttacking;
	protected int _clientMovingToPawnOffset;
	
	private L2Object _target;
	private L2Character _castTarget;
	protected L2Character _attackTarget;
	protected L2Character _followTarget;
	
	L2Skill _skill;
	
	private int _moveToPawnTimeout;
	
	protected AbstractAI(L2Character creature)
	{
		_actor = creature;
	}
	
	@Override
	public L2Character getActor()
	{
		return _actor;
	}
	
	public synchronized void changeIntention(CtrlIntention intention, Object arg0, Object arg1)
	{
		_intention = intention;
		_intentionArg0 = arg0;
		_intentionArg1 = arg1;
	}
	
	@Override
	public final void setIntention(CtrlIntention intention)
	{
		setIntention(intention, null, null);
	}
	
	@Override
	public final void setIntention(final CtrlIntention intention, final Object arg0)
	{
		setIntention(intention, arg0, null);
	}
	
	@Override
	public final void setIntention(final CtrlIntention intention, final Object arg0, final Object arg1)
	{
		if (!_actor.isVisible() || !_actor.hasAI())
		{
			return;
		}
		
		if (intention != AI_INTENTION_FOLLOW && intention != AI_INTENTION_ATTACK)
		{
			stopFollow();
		}
		
		switch (intention)
		{
			case AI_INTENTION_IDLE:
				onIntentionIdle();
				break;
			case AI_INTENTION_ACTIVE:
				onIntentionActive();
				break;
			case AI_INTENTION_REST:
				onIntentionRest();
				break;
			case AI_INTENTION_ATTACK:
				onIntentionAttack((L2Character) arg0);
				break;
			case AI_INTENTION_CAST:
				onIntentionCast((L2Skill) arg0, (L2Object) arg1);
				break;
			case AI_INTENTION_MOVE_TO:
				onIntentionMoveTo((Location) arg0);
				break;
			case AI_INTENTION_FOLLOW:
				onIntentionFollow((L2Character) arg0);
				break;
			case AI_INTENTION_PICK_UP:
				onIntentionPickUp((L2Object) arg0);
				break;
			case AI_INTENTION_INTERACT:
				onIntentionInteract((L2Object) arg0);
				break;
		}
	}
	
	@Override
	public final void notifyEvent(CtrlEvent evt)
	{
		notifyEvent(evt, null, null);
	}
	
	@Override
	public final void notifyEvent(final CtrlEvent evt, final Object arg0)
	{
		notifyEvent(evt, arg0, null);
	}
	
	@Override
	public final void notifyEvent(final CtrlEvent evt, final Object arg0, final Object arg1)
	{
		if ((!_actor.isVisible() && !_actor.isTeleporting()) || !_actor.hasAI())
		{
			return;
		}
		
		switch (evt)
		{
			case EVT_THINK:
				onEvtThink();
				break;
			case EVT_ATTACKED:
				onEvtAttacked((L2Character) arg0);
				break;
			case EVT_AGGRESSION:
				onEvtAggression((L2Character) arg0, ((Number) arg1).intValue());
				break;
			case EVT_STUNNED:
				onEvtStunned((L2Character) arg0);
				break;
			case EVT_SLEEPING:
				onEvtSleeping((L2Character) arg0);
				break;
			case EVT_ROOTED:
				onEvtRooted((L2Character) arg0);
				break;
			case EVT_CONFUSED:
				onEvtConfused((L2Character) arg0);
				break;
			case EVT_MUTED:
				onEvtMuted((L2Character) arg0);
				break;
			case EVT_READY_TO_ACT:
				if (!_actor.isCastingNow())
				{
					onEvtReadyToAct();
				}
				break;
			case EVT_USER_CMD:
				onEvtUserCmd(arg0, arg1);
				break;
			case EVT_ARRIVED:
				onEvtArrived();
				break;
			case EVT_ARRIVED_REVALIDATE:
				onEvtArrivedRevalidate();
				break;
			case EVT_ARRIVED_BLOCKED:
				onEvtArrivedBlocked((Location) arg0);
				break;
			case EVT_FORGET_OBJECT:
				onEvtForgetObject((L2Object) arg0);
				break;
			case EVT_CANCEL:
				onEvtCancel();
				break;
			case EVT_DEAD:
				onEvtDead();
				break;
			case EVT_FAKE_DEATH:
				onEvtFakeDeath();
				break;
			case EVT_FINISH_CASTING:
				onEvtFinishCasting();
				break;
		}
	}
	
	protected abstract void onIntentionIdle();
	
	protected abstract void onIntentionActive();
	
	protected abstract void onIntentionRest();
	
	protected abstract void onIntentionAttack(L2Character target);
	
	protected abstract void onIntentionCast(L2Skill skill, L2Object target);
	
	protected abstract void onIntentionMoveTo(Location arg0);
	
	protected abstract void onIntentionFollow(L2Character target);
	
	protected abstract void onIntentionPickUp(L2Object item);
	
	protected abstract void onIntentionInteract(L2Object object);
	
	protected abstract void onEvtThink();
	
	protected abstract void onEvtAttacked(L2Character attacker);
	
	protected abstract void onEvtAggression(L2Character target, int aggro);
	
	protected abstract void onEvtStunned(L2Character attacker);
	
	protected abstract void onEvtSleeping(L2Character attacker);
	
	protected abstract void onEvtRooted(L2Character attacker);
	
	protected abstract void onEvtConfused(L2Character attacker);
	
	protected abstract void onEvtMuted(L2Character attacker);
	
	protected abstract void onEvtReadyToAct();
	
	protected abstract void onEvtUserCmd(Object arg0, Object arg1);
	
	protected abstract void onEvtArrived();
	
	protected abstract void onEvtArrivedRevalidate();
	
	protected abstract void onEvtArrivedBlocked(Location blocked_at_pos);
	
	protected abstract void onEvtForgetObject(L2Object object);
	
	protected abstract void onEvtCancel();
	
	protected abstract void onEvtDead();
	
	protected abstract void onEvtFakeDeath();
	
	protected abstract void onEvtFinishCasting();
	
	public void moveToPawn(L2Object pawn, int offset)
	{
		if (!_actor.isMovementDisabled())
		{
			if (offset < 10)
			{
				offset = 10;
			}
			
			if (_clientMoving && getTarget() == pawn)
			{
				if (_clientMovingToPawnOffset == offset)
				{
					if (GameTimeController.getInstance().getGameTicks() < _moveToPawnTimeout)
					{
						return;
					}
				}
				else if (_actor.isOnGeodataPath())
				{
					// minimum time to calculate new route is 2 seconds
					if (GameTimeController.getInstance().getGameTicks() < (_moveToPawnTimeout + 10))
					{
						return;
					}
				}
			}
			
			_clientMoving = true;
			_clientMovingToPawnOffset = offset;
			_moveToPawnTimeout = GameTimeController.getInstance().getGameTicks();
			_moveToPawnTimeout += 1000 / GameTimeController.MILLIS_IN_TICK;
			
			if (pawn == null)
			{
				clientActionFailed();
				return;
			}
			
			if (!GeoData.getInstance().canSeeTarget(_actor, pawn))
			{
				offset = 0;
			}
			
			_actor.moveToLocation(pawn.getX(), pawn.getY(), pawn.getZ(), offset);
			
			if (!_actor.isMoving())
			{
				clientActionFailed();
				return;
			}
			
			if (pawn instanceof L2Character)
			{
				if (_actor.isOnGeodataPath())
				{
					_actor.broadcastPacket(new CharMoveToLocation(_actor));
					_clientMovingToPawnOffset = 0;
				}
				else
				{
					_actor.broadcastPacket(new MoveToPawn(_actor, (L2Character) pawn, offset));
				}
			}
			else
			{
				_actor.broadcastPacket(new CharMoveToLocation(_actor));
			}
		}
		else
		{
			clientActionFailed();
		}
	}
	
	public void moveTo(int x, int y, int z)
	{
		if (!_actor.isMovementDisabled())
		{
			_clientMoving = true;
			_clientMovingToPawnOffset = 0;
			
			_actor.moveToLocation(x, y, z, 0);
			
			_actor.broadcastPacket(new CharMoveToLocation(_actor));
		}
		else
		{
			_actor.sendPacket(ActionFailed.STATIC_PACKET);
		}
	}
	
	public void clientStopMoving(Location pos)
	{
		if (_actor.isMoving())
		{
			_actor.stopMove(pos);
		}
		
		_clientMovingToPawnOffset = 0;
		
		if (_clientMoving || pos != null)
		{
			_clientMoving = false;
			
			_actor.broadcastPacket(new StopMove(_actor));
			
			if (pos != null)
			{
				StopRotation sr = new StopRotation(_actor, pos.getHeading(), 0);
				_actor.sendPacket(sr);
				_actor.broadcastPacket(sr);
			}
		}
	}
	
	protected void clientStoppedMoving()
	{
		if (_clientMovingToPawnOffset > 0) // movetoPawn needs to be stopped
		{
			_clientMovingToPawnOffset = 0;
			_actor.broadcastPacket(new StopMove(_actor));
		}
		_clientMoving = false;
	}
	
	public void clientStartAutoAttack()
	{
		if (_actor instanceof L2Summon)
		{
			final L2Summon summon = (L2Summon) _actor;
			if (summon.getOwner() != null)
			{
				summon.getOwner().getAI().clientStartAutoAttack();
			}
			return;
		}
		
		if (!isAutoAttacking())
		{
			if (_actor instanceof L2PcInstance && ((L2PcInstance) _actor).getPet() != null)
			{
				((L2PcInstance) _actor).getPet().broadcastPacket(new AutoAttackStart(((L2PcInstance) _actor).getPet().getObjectId()));
			}
			
			_actor.broadcastPacket(new AutoAttackStart(_actor.getObjectId()));
			setAutoAttacking(true);
		}
		
		AttackStanceTaskManager.getInstance().addAttackStanceTask(_actor);
	}
	
	public void clientStopAutoAttack()
	{
		if (_actor instanceof L2Summon)
		{
			final L2Summon summon = (L2Summon) _actor;
			if (summon.getOwner() != null)
			{
				summon.getOwner().getAI().clientStopAutoAttack();
			}
			return;
		}
		
		final boolean isAutoAttacking = isAutoAttacking();
		
		if (_actor instanceof L2PcInstance)
		{
			if (!AttackStanceTaskManager.getInstance().getAttackStanceTask(_actor) && isAutoAttacking)
			{
				AttackStanceTaskManager.getInstance().addAttackStanceTask(_actor);
			}
		}
		else if (isAutoAttacking)
		{
			_actor.broadcastPacket(new AutoAttackStop(_actor.getObjectId()));
			setAutoAttacking(false);
		}
	}
	
	protected void clientNotifyDead()
	{
		_actor.broadcastPacket(new Die(_actor));
		
		setIntention(AI_INTENTION_IDLE);
		setTarget(null);
		setAttackTarget(null);
		setCastTarget(null);
		
		stopFollow();
	}
	
	public void describeStateToPlayer(L2PcInstance player)
	{
		if (_clientMoving)
		{
			if (_clientMovingToPawnOffset != 0 && _followTarget != null)
			{
				MoveToPawn msg = new MoveToPawn(_actor, _followTarget, _clientMovingToPawnOffset);
				player.sendPacket(msg);
			}
			else
			{
				CharMoveToLocation msg = new CharMoveToLocation(_actor);
				player.sendPacket(msg);
			}
		}
	}
	
	public synchronized void startFollow(L2Character target)
	{
		if (_followTask != null)
		{
			_followTask.cancel(false);
			_followTask = null;
		}
		
		_followTarget = target;
		_followTask = ThreadPoolManager.getInstance().scheduleAiAtFixedRate(new FollowTask(), 0, FOLLOW_INTERVAL);
	}
	
	public synchronized void startFollow(L2Character target, int range)
	{
		if (_followTask != null)
		{
			_followTask.cancel(false);
			_followTask = null;
		}
		
		_followTarget = target;
		_followTask = ThreadPoolManager.getInstance().scheduleAiAtFixedRate(new FollowTask(range), 0, ATTACK_FOLLOW_INTERVAL);
	}
	
	public synchronized void stopFollow()
	{
		if (_followTask != null)
		{
			_followTask.cancel(false);
			_followTask = null;
		}
		
		_followTarget = null;
	}
	
	protected synchronized L2Character getFollowTarget()
	{
		return _followTarget;
	}
	
	public synchronized L2Character getFollowTargetPhantom()
	{
		return _followTarget;
	}
	
	protected L2Object getTarget()
	{
		return _target;
	}
	
	protected void setTarget(final L2Object target)
	{
		_target = target;
	}
	
	protected void setCastTarget(final L2Character target)
	{
		_castTarget = target;
	}
	
	public L2Character getCastTarget()
	{
		return _castTarget;
	}
	
	protected void setAttackTarget(final L2Character target)
	{
		_attackTarget = target;
	}
	
	@Override
	public L2Character getAttackTarget()
	{
		return _attackTarget;
	}
	
	public synchronized boolean isAutoAttacking()
	{
		return _clientAutoAttacking;
	}
	
	public synchronized void setAutoAttacking(boolean isAutoAttacking)
	{
		_clientAutoAttacking = isAutoAttacking;
	}
	
	public synchronized Object get_intentionArg0()
	{
		return _intentionArg0;
	}
	
	public synchronized void set_intentionArg0(final Object intentionArg0)
	{
		_intentionArg0 = intentionArg0;
	}
	
	public synchronized Object get_intentionArg1()
	{
		return _intentionArg1;
	}
	
	public synchronized void set_intentionArg1(final Object intentionArg1)
	{
		_intentionArg1 = intentionArg1;
	}
	
	public void stopAITask()
	{
		stopFollow();
	}
	
	@Override
	public synchronized CtrlIntention getIntention()
	{
		return _intention;
	}
	
	protected void clientActionFailed()
	{
		if (_actor instanceof L2PcInstance)
		{
			_actor.sendPacket(ActionFailed.STATIC_PACKET);
		}
	}
	
}
