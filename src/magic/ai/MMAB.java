package magic.ai;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import magic.model.MagicGame;
import magic.model.MagicGameLog;
import magic.model.MagicPlayer;
import magic.model.event.MagicEvent;
import magic.model.phase.MagicPhase;

public class MMAB implements MagicAI {
    
    private static final int INITIAL_MAX_DEPTH=120;
    private static final int INITIAL_MAX_GAMES=12000;
    private static final int         MAX_DEPTH=120;
    private static final int         MAX_GAMES=12000;
    private static final long      SEC_TO_NANO=1000000000L;

    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    
    private final boolean LOGGING;
    private final boolean CHEAT;
    private ArtificialPruneScore pruneScore = new ArtificialMultiPruneScore();

    MMAB() {
        //default: no logging, no cheats
        this(false, false);
    }
    
    MMAB(final boolean log, final boolean cheat) {
        LOGGING = log || (System.getProperty("debug") != null);
        CHEAT = cheat;
    }
    
    private void log(final String message) {
        MagicGameLog.log(message);
        if (LOGGING) {
            System.err.println(message);
        }
    }
    
    public Object[] findNextEventChoiceResults(final MagicGame sourceGame, final MagicPlayer scorePlayer) {
        final long startTime = System.currentTimeMillis();

        // copying the game is necessary because for some choices game scores might be calculated, 
        // find all possible choice results.
        MagicGame choiceGame = new MagicGame(sourceGame,scorePlayer);
        final MagicEvent event = choiceGame.getNextEvent();
        final List<Object[]> choices = event.getArtificialChoiceResults(choiceGame);
        final int size = choices.size();
        choiceGame = null;
        
        assert size != 0 : "ERROR: no choices available for MMAB";
        
        // single choice result.
        if (size == 1) {
            return sourceGame.map(choices.get(0));
        }
        
        // submit jobs
        final ArtificialScoreBoard scoreBoard = new ArtificialScoreBoard();
        final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        final List<ArtificialChoiceResults> achoices=new ArrayList<ArtificialChoiceResults>();
        final int artificialLevel = sourceGame.getArtificialLevel(scorePlayer.getIndex());
        final int mainPhases = artificialLevel;
        final long slice = artificialLevel * Math.min(SEC_TO_NANO, (THREADS * SEC_TO_NANO) / size);
        for (final Object[] choice : choices) {
            final ArtificialChoiceResults achoice=new ArtificialChoiceResults(choice);
            achoices.add(achoice);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    final MagicGame workerGame=new MagicGame(sourceGame,scorePlayer);
                    if (!CHEAT) {
                        workerGame.setKnownCards();
                    }
                    workerGame.setFastChoices(true);
                    final MMABWorker worker=new MMABWorker(
                        (int)Thread.currentThread().getId(),
                        workerGame,
                        scoreBoard
                    );
                    worker.evaluateGame(achoice, getPruneScore(), System.nanoTime() + slice);
                    updatePruneScore(achoice.aiScore.getScore());
                }
            });
        }
        executor.shutdown();
        try {
            // wait for artificialLevel seconds for jobs to finish
            executor.awaitTermination(artificialLevel, TimeUnit.SECONDS);
        } catch (final InterruptedException ex) {
            throw new RuntimeException(ex);
        } finally {
            // force termination of workers
            executor.shutdownNow();
        }
        
        // select the best scoring choice result.
        ArtificialScore bestScore = ArtificialScore.INVALID_SCORE;
        ArtificialChoiceResults bestAchoice = achoices.get(0);
        for (final ArtificialChoiceResults achoice : achoices) {
            if (bestScore.isBetter(achoice.aiScore,true)) {
                bestScore = achoice.aiScore;
                bestAchoice = achoice;                
            }
        }

        // Logging.
        final long timeTaken = System.currentTimeMillis() - startTime;
        log("MMAB" + 
            " index=" + scorePlayer.getIndex() +
            " life=" + scorePlayer.getLife() +
            " phase=" + sourceGame.getPhase().getType() + 
            " time=" + timeTaken + 
            " main=" + mainPhases +
            " slice=" + (slice/1000000)
            );
        for (final ArtificialChoiceResults achoice : achoices) {
            log((achoice == bestAchoice ? "* " : "  ") + achoice);
        }

        return sourceGame.map(bestAchoice.choiceResults);
    }

    private void updatePruneScore(final int score) {
        pruneScore = pruneScore.getPruneScore(score,true);
    }
    
    private ArtificialPruneScore getPruneScore() {
        return pruneScore;
    }
}

class MMABWorker {
    
    private final int id;
    private final MagicGame game;
    private final ArtificialScoreBoard scoreBoard;

    private int gameCount;
    
    MMABWorker(final int id,final MagicGame game,final ArtificialScoreBoard scoreBoard) {
        this.id=id;
        this.game=game;
        this.scoreBoard=scoreBoard;
    }
    
    private ArtificialScore runGame(final Object[] nextChoiceResults, final ArtificialPruneScore pruneScore, final int depth, final long maxTime) {
        game.startActions();
        
        if (nextChoiceResults!=null) {
            game.executeNextEvent(nextChoiceResults);
        }
        
        if (System.nanoTime() > maxTime) {
            final ArtificialScore aiScore=new ArtificialScore(game.getScore(),depth);
            game.undoActions();
            gameCount++;
            return aiScore;
        }

        // Play game until given end turn for all possible choices.
        while (!game.isFinished()) {
            if (!game.hasNextEvent()) {
                game.executePhase();
                                
                // Caching of best score for game situations.
                if (game.cacheState()) {
                    final long gameId=game.getGameId(pruneScore.getScore());
                    ArtificialScore bestScore=scoreBoard.getGameScore(gameId);
                    if (bestScore==null) {
                        bestScore=runGame(null,pruneScore,depth,maxTime);
                        scoreBoard.setGameScore(gameId,bestScore.getScore(-depth));
                    } else {
                        bestScore=bestScore.getScore(depth);
                    }
                    game.undoActions();
                    return bestScore;
                }
                continue;
            }
        
            final MagicEvent event=game.getNextEvent();

            if (!event.hasChoice()) {
                game.executeNextEvent(MagicEvent.NO_CHOICE_RESULTS);
                continue;
            }

            final List<Object[]> choiceResultsList=event.getArtificialChoiceResults(game);
            final int nrOfChoices=choiceResultsList.size();
            
            assert nrOfChoices > 0 : "nrOfChoices is 0";
            
            if (nrOfChoices==1) {
                game.executeNextEvent(choiceResultsList.get(0));
                continue;
            }
            
            final long slice = (maxTime - System.nanoTime()) / nrOfChoices;
            final boolean best=game.getScorePlayer()==event.getPlayer();
            ArtificialScore bestScore=ArtificialScore.INVALID_SCORE;
            ArtificialPruneScore newPruneScore=pruneScore;
            for (final Object[] choiceResults : choiceResultsList) {
                final ArtificialScore score=runGame(choiceResults, newPruneScore, depth + 1, System.nanoTime() + slice);
                if (bestScore.isBetter(score,best)) {
                    bestScore=score;
                    // Stop when best score can no longer become the best score at previous levels.
                    if (pruneScore.pruneScore(bestScore.getScore(),best)) {
                        break;
                    }
                    newPruneScore=newPruneScore.getPruneScore(bestScore.getScore(),best);
                }
            }
            game.undoActions();
            return bestScore;
        }

        // Game is finished.
        final ArtificialScore aiScore=new ArtificialScore(game.getScore(),depth);
        game.undoActions();
        gameCount++;
        return aiScore;
    }

    void evaluateGame(final ArtificialChoiceResults aiChoiceResults, final ArtificialPruneScore pruneScore, long maxTime) {
        gameCount = 0;
        
        aiChoiceResults.worker    = id;
        aiChoiceResults.aiScore   = runGame(game.map(aiChoiceResults.choiceResults),pruneScore,0,maxTime);
        aiChoiceResults.gameCount = gameCount;

        game.undoAllActions();
    }
}
