def color = new MagicStatic(MagicLayer.Color, MagicStatic.Forever) {
    @Override
    public int getColorFlags(final MagicPermanent permanent,final int flags) {
        return MagicColor.Green.getMask();
    }
};
[
    new MagicWhenBlocksOrBecomesBlockedTrigger() {
        @Override
        public MagicEvent executeTrigger(final MagicGame game,final MagicPermanent permanent,final MagicPermanent blocker) {
            final MagicPermanent target = permanent == blocker ? blocker.getBlockedCreature() : blocker;
            return new MagicEvent(
                permanent,
                target,
                this,
                "RN becomes green."
            );
        }
        @Override
        public void executeEvent(final MagicGame game, final MagicEvent event) {
            event.processRefPermanent(game, {
                final MagicPermanent permanent ->
                game.doAction(new MagicAddStaticAction(creature, color));
            });
        }
    }
]
