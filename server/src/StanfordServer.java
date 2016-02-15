import edu.delaware.nlp.Protobuf;
import edu.delaware.nlp.NlpServiceGrpc;
import com.google.protobuf.TextFormat;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.dcoref.CorefCoreAnnotations.CorefChainAnnotation;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation;
import edu.stanford.nlp.dcoref.CorefChain;
import edu.stanford.nlp.trees.TreeCoreAnnotations.TreeAnnotation;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.CharacterOffsetBeginAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.trees.CollinsHeadFinder;

import java.util.*;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicInteger;

import edu.stanford.nlp.trees.TypedDependency;
import edu.stanford.nlp.ling.HasOffset;

// The codes below are largely based on http://www.grpc.io/docs/tutorials/basic/java.html.

public class StanfordServer {
    private static final Logger logger = Logger.getLogger(StanfordServer.class.getName());
    private final int port;
    private final int maxConcurrentCalls;
    private final int maxParseSeconds;
    private Server server;

    public StanfordServer(int port, int maxConcurrentCalls, int maxParseSeconds) {
        this.port = port;
        this.maxConcurrentCalls = maxConcurrentCalls;
	this.maxParseSeconds = maxParseSeconds;
    }

    /**
     * Start serving requests.
     */
    public void start() throws IOException {
        server = NettyServerBuilder.forPort(port).maxConcurrentCallsPerConnection(maxConcurrentCalls)
                .addService(NlpServiceGrpc.bindService(new StanfordService(maxParseSeconds)))
                .build()
                .start();
        logger.info("Server started, listening on " + port + ", max concurrent calls: " + maxConcurrentCalls);
	logger.info("Max parsing seconds: " + maxParseSeconds);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may has been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                StanfordServer.this.stop();
                System.err.println("*** server shut down");
            }
        });
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args) {
        // Two command-line arguments, the listening port and max concurrent calls.
        int port = Integer.parseInt(args[0]);
        int maxConcurrentCalls = Integer.parseInt(args[1]);
	int maxParseSeconds = Integer.parseInt(args[2]);
        StanfordServer server = new StanfordServer(port, maxConcurrentCalls, maxParseSeconds);
        try {
            server.start();
            server.blockUntilShutdown();
        } catch (InterruptedException e) {
            System.exit(1);
        } catch (IOException e) {
            System.exit(1);
        }
    }

    private static class StanfordService implements NlpServiceGrpc.NlpService {

        private StanfordCoreNLP pipeline;
        private CollinsHeadFinder headFinder;
        private final ReentrantReadWriteLock readwriteLock;
        private static final Logger logger = Logger.getLogger(StanfordService.class.getName());
	private int maxParseSeconds;
	
        // After processing 10000 documents, reload the whole pipeline to release
        // resources. There are some potential memory leaks.
        private final int reloadCount = 10000;
        private AtomicInteger processedCount;

        StanfordService(int maxParseSeconds) {
	    this.maxParseSeconds = maxParseSeconds;
            loadPipeline();
            readwriteLock = new ReentrantReadWriteLock();
            processedCount = new AtomicInteger();
        }

        private void loadPipeline() {
            pipeline = null;
            headFinder = null;
            // Initialize StanfordNLP pipeline.
            // creates a StanfordCoreNLP object, with POS tagging, lemmatization, NER, parsing, and coreference resolution
            Properties props = new Properties();
            // props.setProperty("annotators", "tokenize, ssplit, pos, lemma, ner, parse, dcoref");
	    // 300 seconds for the whole parsing process for a document.
	    if (maxParseSeconds > 0) {
		props.setProperty("parse.maxtime", Integer.toString(maxParseSeconds * 1000));
	    }
            props.setProperty("annotators", "tokenize, ssplit, pos, lemma, parse");
            //	    props.setProperty("annotators", "tokenize, ssplit");
            pipeline = new StanfordCoreNLP(props);
            headFinder =  new CollinsHeadFinder();

        }

        @Override
        public void processDocument(Protobuf.Request request, StreamObserver<Protobuf.Response> responseObserver) {
            readwriteLock.readLock().lock();
            Protobuf.Response.Builder rbuilder = Protobuf.Response.newBuilder();
	    rbuilder.setSuccess(true);
	    if (request.getRequestType() == Protobuf.Request.RequestType.SPLIT) {
		for (Protobuf.Document doc : request.getDocumentList()) {
		    try {
			rbuilder.addDocument(split_sentence(doc));
		    } catch(NullPointerException e) {
			// System.out.println("NullPointerException caught");
			// rbuilder.addAllDocument(request.getDocumentList());
			// rbuilder.setSuccess(false);
			// If timeouts, add the original document.
			// Clear tokens and sentences which may contain incomplete results.
			Protobuf.Document.Builder dbuilder = doc.toBuilder();
			dbuilder.clearToken();
			dbuilder.clearSentence();
			rbuilder.addDocument(dbuilder);
		    }
		}
	    } else if (request.getRequestType() == Protobuf.Request.RequestType.PARSE) {
		for (Protobuf.Document doc : request.getDocumentList()) {
		    try {
			rbuilder.addDocument(full_parse(doc));
		    } catch(NullPointerException e) {
			// System.out.println("NullPointerException caught");
			// rbuilder.addAllDocument(request.getDocumentList());
			// rbuilder.setSuccess(false);
			// If timeouts, add the original document.
			// Clear tokens and sentences which may contain incomplete results.
                        Protobuf.Document.Builder dbuilder = doc.toBuilder();
			dbuilder.clearToken();
			dbuilder.clearSentence();
			rbuilder.addDocument(dbuilder);			
		    }
		}
	    } else {
	    }

            responseObserver.onNext(rbuilder.build());
            responseObserver.onCompleted();

            // Add processed documents.
            processedCount.addAndGet(request.getDocumentList().size());
            readwriteLock.readLock().unlock();

	    /*
            // Potentially reload the whole pipeline to release resources.
            if (processedCount.intValue() >= reloadCount) {
                readwriteLock.writeLock().lock();
                // There could be multiple theads waiting for the write locks.
                // When the first one get the lock, and second one needs to check if
                // the processed count is still larger than reload count (in this case
                // it wouldn't be. So the second and other threads waiting for the lock
                // can skip the reloading.
                if (processedCount.intValue() >= reloadCount) {
                    logger.info("Reload pipeline at processed docs: " + processedCount.intValue());
                    loadPipeline();
                    processedCount.set(0);
                }
                readwriteLock.writeLock().unlock();
            }
	    */
        }


        private Protobuf.Document split_sentence(Protobuf.Document protoDoc) {
            String text = protoDoc.getText();
            Protobuf.Document.Builder dbuilder = protoDoc.toBuilder();

            // create an empty Annotation just with the given text
            Annotation document = new Annotation(text);

            // run all Annotators on this text
            pipeline.annotate(document);

            // these are all the sentences in this document
            // a CoreMap is essentially a Map that uses class objects as keys and has values with custom types
            List<CoreMap> sentences = document.get(SentencesAnnotation.class);

            int tokenIndex = 0;
            int sentIndex = 0;
            for (CoreMap sentence : sentences) {
                // traversing the words in the current sentence
                // a CoreLabel is a CoreMap with additional token-specific methods
                int sentenceBoundary = -1;
                HashMap<Integer, Integer> indexMap = new HashMap<Integer, Integer>();
                Protobuf.Sentence.Builder sbuilder = Protobuf.Sentence.newBuilder();

                for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
                    if (sentenceBoundary == -1) {
                        sbuilder.setTokenStart(tokenIndex);
                    }

                    // Note that if any of the field is the default value, it won't be printed.
                    // For example, if the first token is "I", the its char_start, char_end and
                    // token_id won't be printed by TextFormat.printToString().
                    Protobuf.Token.Builder tbuilder = Protobuf.Token.newBuilder();
                    tbuilder.setWord(token.originalText());
                    // tbuilder.setLemma(token.lemma());
                    tbuilder.setCharStart(token.beginPosition());
                    // token.endPosition() is the position after the last character.
                    tbuilder.setCharEnd(token.endPosition() - 1);
                    tbuilder.setIndex(tokenIndex);
                    dbuilder.addToken(tbuilder);
                    indexMap.put(token.index(), tokenIndex);
                    sentenceBoundary = tokenIndex;
                    tokenIndex++;
                }

                sbuilder.setTokenEnd(tokenIndex - 1);
                sbuilder.setIndex(sentIndex);
                dbuilder.addSentence(sbuilder);

                sentIndex++;

            }
            // dbuilder.clearText();
            return dbuilder.build();
        }

        private Protobuf.Document full_parse(Protobuf.Document protoDoc) {
//            System.out.println(protoDoc.getDocId());
            String text = protoDoc.getText();
            Protobuf.Document.Builder dbuilder = protoDoc.toBuilder();

            // create an empty Annotation just with the given text
            Annotation document = new Annotation(text);

            // run all Annotators on this text
            pipeline.annotate(document);

            // these are all the sentences in this document
            // a CoreMap is essentially a Map that uses class objects as keys and has values with custom types
            List<CoreMap> sentences = document.get(SentencesAnnotation.class);

            int tokenIndex = 0;
            int sentIndex = 0;
            for (CoreMap sentence : sentences) {
                // traversing the words in the current sentence
                // a CoreLabel is a CoreMap with additional token-specific methods
                int sentenceBoundary = -1;
                HashMap<Integer, Integer> indexMap = new HashMap<Integer, Integer>();
                Protobuf.Sentence.Builder sbuilder = Protobuf.Sentence.newBuilder();

                for (CoreLabel token : sentence.get(TokensAnnotation.class)) {
                    if (sentenceBoundary == -1) {
                        sbuilder.setTokenStart(tokenIndex);
                    }
                    // this is the POS tag of the token
                    String pos = token.get(PartOfSpeechAnnotation.class);

                    // Note that if any of the field is the default value, it woun't be printed.
                    // For example, if the first token is "I", the its char_start, char_end and
                    // token_id won't be printed by TextFormat.printToString().
                    Protobuf.Token.Builder tbuilder = Protobuf.Token.newBuilder();
                    tbuilder.setWord(token.originalText());
                    tbuilder.setPos(pos);
                    tbuilder.setLemma(token.lemma());
                    tbuilder.setCharStart(token.beginPosition());
                    // token.endPosition() is the position after the last character.
                    tbuilder.setCharEnd(token.endPosition() - 1);
                    tbuilder.setIndex(tokenIndex);
                    dbuilder.addToken(tbuilder);
                    indexMap.put(token.index(), tokenIndex);
                    sentenceBoundary = tokenIndex;
                    tokenIndex++;
                }

                sbuilder.setTokenEnd(tokenIndex - 1);
                sbuilder.setIndex(sentIndex);
                sentIndex++;

                // this is the parse tree of the current sentence
                Tree nextTree = sentence.get(TreeAnnotation.class);
                Queue<Tree> treeQueue = new LinkedList<Tree>();
                Queue<Integer> parentQueue = new LinkedList<Integer>();
                int treeIndex = 0;
                parentQueue.add(0);
                while (nextTree != null) {
                    int parentIndex = parentQueue.poll();
                    // Get the head leaf.
                    Tree head = nextTree.headTerminal(headFinder);
                    List<Tree> leaves = head.getLeaves();
                    assert leaves.size() == 1;
                    Tree only_leaf = leaves.get(0);

                    // Get char start and end for the head token.
                    int head_start = ((HasOffset) only_leaf.label()).beginPosition();
                    // It looks the end position is the last char index + 1.
                    int head_end = ((HasOffset) only_leaf.label()).endPosition() - 1;

                    // Get char start and end for the phrase.
                    List<Tree> treeLeaves = nextTree.getLeaves();
                    Tree first_leaf = treeLeaves.get(0);
                    Tree last_leaf = treeLeaves.get(treeLeaves.size() - 1);
                    int phrase_start = ((HasOffset) first_leaf.label()).beginPosition();
                    // It looks the end position is the last char index + 1.
                    int phrase_end = ((HasOffset) last_leaf.label()).endPosition() - 1;

                    assert phrase_end >= phrase_start;

                    Protobuf.Sentence.Constituent.Builder cbuilder = Protobuf.Sentence.Constituent.newBuilder();
                    cbuilder.setLabel(nextTree.label().value());
                    cbuilder.setCharStart(phrase_start);
                    cbuilder.setCharEnd(phrase_end);
                    cbuilder.setHeadCharStart(head_start);
                    cbuilder.setHeadCharEnd(head_end);
                    cbuilder.setIndex(treeIndex);
                    cbuilder.setParent(parentIndex);
                    // Add children index to its parent.
                    if (parentIndex < treeIndex) {
                        sbuilder.getConstituentBuilder(parentIndex).addChildren(treeIndex);
                    }
                    for (Tree child : nextTree.children()) {
                        treeQueue.add(child);
                        parentQueue.add(treeIndex);
                    }
                    sbuilder.addConstituent(cbuilder);
                    treeIndex++;
                    nextTree = treeQueue.poll();
                }


//                sbuilder.setParse(tree.toString());

                // this is the Stanford dependency graph of the current sentence
                SemanticGraph dependencies = sentence.get(CollapsedCCProcessedDependenciesAnnotation.class);
                //                System.out.println(dependencies.toString());
                // Add root relations. The root links to itself with the relation "root".
                Collection<IndexedWord> roots = dependencies.getRoots();
                for (IndexedWord root : roots) {
                    int rootIndex = indexMap.get(root.index());
                    Protobuf.Sentence.Dependency.Builder depBuilder = Protobuf.Sentence.Dependency.newBuilder();
                    depBuilder.setDepIndex(rootIndex);
                    depBuilder.setGovIndex(rootIndex);
                    depBuilder.setRelation("root");
                    sbuilder.addDependency(depBuilder);
                }
                HashMap<Integer, TreeSet<Integer>> childrenMap = new HashMap<Integer, TreeSet<Integer>>();

                // This only gets basic dependencies.
                // Collection<TypedDependency> typedDeps = dependencies.typedDependencies();
                // for (TypedDependency typedDep : typedDeps) {

                // Correct way to get collapsed and ccprocessed relations.
                for (SemanticGraphEdge edge : dependencies.edgeIterable()) {
                    IndexedWord gov = edge.getGovernor();
                    IndexedWord dep = edge.getDependent();

                    int depIndex = indexMap.get(dep.index());
                    int govIndex = indexMap.get(gov.index());

                    // Set govIndex to depIndex if dep is the root token.
                    /*
                      int govIndex = depIndex;
                      if (gov.index() > 0) {

                      }
                    */

                    // Only toString can get the collapsed and ccprocessed relations.
                    // Neither getShortName() and getLongName() can. Don't know why.
                    String depTag = edge.getRelation().toString();
                    // String depTag = edge.getRelation().getShortName();

                    Protobuf.Sentence.Dependency.Builder depBuilder = Protobuf.Sentence.Dependency.newBuilder();
                    depBuilder.setDepIndex(depIndex);
                    depBuilder.setGovIndex(govIndex);
                    depBuilder.setRelation(depTag);
                    sbuilder.addDependency(depBuilder);

                }
                dbuilder.addSentence(sbuilder);
            }

            // This is the coreference link graph
            // Each chain stores a set of mentions that link to each other,
            // along with a method for getting the most representative mention
            // Both sentence and token offsets start at 1!
            //        Map<Integer, CorefChain> graph =
            //                document.get(CorefChainAnnotation.class);

            //            System.out.println(TextFormat.printToString(dbuilder.build()));
            return dbuilder.build();
        }
    }
}
