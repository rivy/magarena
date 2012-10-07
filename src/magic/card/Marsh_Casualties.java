package magic.card;

import magic.model.MagicGame;
import magic.model.MagicManaCost;
import magic.model.MagicPayedCost;
import magic.model.MagicPermanent;
import magic.model.MagicPlayer;
import magic.model.action.MagicChangeTurnPTAction;
import magic.model.action.MagicPlayerAction;
import magic.model.choice.MagicKickerChoice;
import magic.model.choice.MagicTargetChoice;
import magic.model.event.MagicEvent;
import magic.model.event.MagicSpellCardEvent;
import magic.model.stack.MagicCardOnStack;
import magic.model.target.MagicTarget;
import magic.model.target.MagicTargetFilter;

import java.util.Collection;

public class Marsh_Casualties {
    public static final MagicSpellCardEvent S = new MagicSpellCardEvent() {
        @Override
        public MagicEvent getEvent(final MagicCardOnStack cardOnStack,final MagicPayedCost payedCost) {
            return new MagicEvent(
                    cardOnStack,
                    new MagicKickerChoice(MagicTargetChoice.NEG_TARGET_PLAYER,MagicManaCost.THREE,false),
                    this,
                    "Creatures target player$ controls get -1/-1 until end of turn. " +
                    "If SN was kicked$, those creatures get -2/-2 until end of turn instead.");
        }
        @Override
        public void executeEvent(
                final MagicGame game,
                final MagicEvent event,
                final Object[] choiceResults) {
            event.processTargetPlayer(game,choiceResults,0,new MagicPlayerAction() {
                public void doAction(final MagicPlayer player) {
                    final int amount=(Integer)choiceResults[1]>0?-2:-1;
                    final Collection<MagicPermanent> targets=
                        game.filterPermanents(player,MagicTargetFilter.TARGET_CREATURE_YOU_CONTROL);
                    for (final MagicPermanent target : targets) {
                        game.doAction(new MagicChangeTurnPTAction(target,amount,amount));
                    }
                }
            });
        }
    };
}
