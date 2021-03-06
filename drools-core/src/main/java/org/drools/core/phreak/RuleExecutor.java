package org.drools.core.phreak;

import org.drools.core.common.AgendaItem;
import org.drools.core.common.InternalAgenda;
import org.drools.core.common.InternalAgendaGroup;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.InternalRuleFlowGroup;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.reteoo.LeftTuple;
import org.drools.core.reteoo.PathMemory;
import org.drools.core.reteoo.RuleTerminalNode;
import org.drools.core.rule.Rule;
import org.drools.core.spi.AgendaFilter;
import org.drools.core.spi.PropagationContext;
import org.drools.core.util.LinkedList;
import org.drools.core.util.index.LeftTupleList;

/**
 * Created with IntelliJ IDEA.
 * User: mdproctor
 * Date: 03/05/2013
 * Time: 15:49
 * To change this template use File | Settings | File Templates.
 */
public class RuleExecutor {
    private PathMemory rmem;

    private static RuleNetworkEvaluator networkEvaluator = new RuleNetworkEvaluator();

    private RuleAgendaItem ruleAgendaItem;

    private LeftTupleList tupleList;

    private boolean dirty;

    private boolean declarativeAgendaEnabled;

    public RuleExecutor(final PathMemory rmem,
                        RuleAgendaItem ruleAgendaItem,
                        boolean declarativeAgendaEnabled) {
        this.rmem = rmem;
        this.ruleAgendaItem = ruleAgendaItem;
        this.tupleList = new LeftTupleList();
        this.declarativeAgendaEnabled = declarativeAgendaEnabled;
    }

    public int evaluateNetwork(InternalWorkingMemory wm,
                               final AgendaFilter filter,
                               int fireCount,
                               int fireLimit) {
        LinkedList<StackEntry> outerStack = new LinkedList<StackEntry>();

        this.networkEvaluator.evaluateNetwork(rmem, outerStack, this, wm);
        setDirty(false);
        wm.executeQueuedActions();

        //int fireCount = 0;
        int localFireCount = 0;
        if (!tupleList.isEmpty()) {
            RuleTerminalNode rtn = (RuleTerminalNode) rmem.getNetworkNode();
            Rule rule = rtn.getRule();

            InternalAgenda agenda = (InternalAgenda) wm.getAgenda();
            int salience = rule.getSalience().getValue(null, null, null); // currently all branches have the same salience for the same rule

            if (isDeclarativeAgendaEnabled()) {
                // Network Evaluation can notify meta rules, which should be given a chance to fire first
                RuleAgendaItem nextRule = agenda.peekNextRule();
                if ( !isHighestSalience( nextRule, salience ) ) {
                    return localFireCount;
                }
            }

            while (!tupleList.isEmpty() ) {
                LeftTuple leftTuple = tupleList.removeFirst();

                rtn = (RuleTerminalNode) leftTuple.getSink(); // branches result in multiple RTN's for a given rule, so unwrap per LeftTuple
                rule = rtn.getRule();

                PropagationContext pctx = leftTuple.getPropagationContext();
                pctx = RuleTerminalNode.findMostRecentPropagationContext( leftTuple,
                                                                          pctx );

                //check if the rule is not effective or
                // if the current Rule is no-loop and the origin rule is the same then return
                if (isNotEffective(wm, rtn, rule, leftTuple, pctx)) {
                    continue;
                }

                AgendaItem item = ( AgendaItem ) leftTuple.getObject();
                if ( item == null ) {
                    item = agenda.createAgendaItem( leftTuple, salience, pctx, rtn, ruleAgendaItem, ruleAgendaItem.getAgendaGroup(), ruleAgendaItem.getRuleFlowGroup() );
                    leftTuple.setObject( item );
                } else {
                    item.setPropagationContext( pctx );
                }
                if ( agenda.getActivationsFilter() != null && !agenda.getActivationsFilter().accept( item,
                                                                                                     pctx,
                                                                                                     wm,
                                                                                                     rtn ) ) {
                    continue;
                }
                item.setQueued(true);
                if ( filter == null || filter.accept(item) ) {
                    agenda.fireActivation( item );
                    localFireCount++;
                }

                RuleAgendaItem nextRule = agenda.peekNextRule();
                if ( haltRuleFiring( nextRule, fireCount, fireLimit, localFireCount, agenda, salience ) ) {
                    break; // another rule has high priority and is on the agenda, so evaluate it first
                }
                if ( isDirty() ) {
                    ruleAgendaItem.dequeue();
                    setDirty( false );
                    this.networkEvaluator.evaluateNetwork( rmem, outerStack, this, wm);
                }
                wm.executeQueuedActions();

                if ( tupleList.isEmpty() && !outerStack.isEmpty() ) {
                    // the outer stack is nodes needing evaluation, once all rule firing is done
                    // such as window expiration, which must be done serially
                    StackEntry entry = outerStack.removeFirst();
                    this.networkEvaluator.evalStackEntry(entry, outerStack, outerStack, this, wm);
                }
            }
        }

        if ( !dirty && tupleList.isEmpty() ) {
            ruleAgendaItem.remove();
        }

        return localFireCount;
    }

    public RuleAgendaItem getRuleAgendaItem() {
        return ruleAgendaItem;
    }

    private boolean isNotEffective(InternalWorkingMemory wm,
                                   RuleTerminalNode rtn,
                                   Rule rule,
                                   LeftTuple leftTuple,
                                   PropagationContext pctx) {
        // NB. stopped setting the LT.object to Boolean.TRUE, that Reteoo did.
        if ( (!rule.isEffective( leftTuple,
                                 rtn,
                                 wm )) ||
             (rule.isNoLoop() && rule.equals( pctx.getRuleOrigin() )) ) {
            return true;
        }

        if ( rule.getCalendars() != null ) {
            long timestamp = wm.getSessionClock().getCurrentTime();
            for ( String cal : rule.getCalendars() ) {
                if ( !wm.getCalendars().get( cal ).isTimeIncluded( timestamp ) ) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean haltRuleFiring(RuleAgendaItem nextRule,
                                   int fireCount,
                                   int fireLimit,
                                   int localFireCount,
                                   InternalAgenda agenda,
                                   int salience) {
        if ( !agenda.continueFiring( 0 ) || !isHighestSalience( nextRule, salience ) || (fireLimit >= 0 && (localFireCount + fireCount >= fireLimit)) ) {
            return true;
        }
        return false;
    }

    public boolean isHighestSalience(RuleAgendaItem nextRule,
                                     int currentSalience) {
        return (nextRule == null) || nextRule.getRule().getSalience().getValue( null, null, null ) <= currentSalience;
    }

    public LeftTupleList getLeftTupleList() {
        return this.tupleList;
    }


    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(final boolean dirty) {
        this.dirty = dirty;
    }

    public boolean isDeclarativeAgendaEnabled() {
        return this.declarativeAgendaEnabled;
    }


}
