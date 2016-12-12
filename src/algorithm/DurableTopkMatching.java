package algorithm;

import java.util.BitSet;

import graph.pattern.PatternGraph;
import graph.version.Graph;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;

import graph.pattern.PatternNode;
import graph.version.Edge;
import graph.version.Node;
import graph.version.loader.LoaderDBLP;
import system.Config;

/**
 * DurableMatching Algorithm class
 * 
 * @author ksemer
 */
public class DurableTopkMatching {
	// ===============================================================

	// pattern graph
	private PatternGraph pg;

	// query interval
	private BitSet iQ;

	// Ranking structure
	private Map<Integer, TreeMap<Integer, Set<Node>>> Rank = new HashMap<>();

	// threshold for time duration
	private int threshold = Integer.MAX_VALUE;

	// if the query is continuously
	private boolean continuously;

	// stores the k matches
	private PriorityQueue<Match> topkMatches = new PriorityQueue<Match>(11, new MatchComparator(false));

	// stores all the matches
	private Set<String> matchesFound;

	// stores all chosen theta
	private Set<Integer> durationMaxRanking;

	// minimum checked threshold Max, Adaptive
	private int minimumCheckedTheta;

	// total recursions
	private int totalRecursions = 0;

	// size of rank
	private int sizeOfRank = 0;

	// k matches
	private int k;

	private boolean isTerminated = false;

	// utility value for adaptive ranking
	private int canDSize = Integer.MAX_VALUE;

	// recursions per theta run
	private int recursionsPerTheta;

	// total time of algorithm
	private long totalTime;

	private int rankingStrategy;

	// time limit for algorithm execution
	private long timeLimit;

	// ===============================================================

	/**
	 * Constructor
	 * 
	 * @param lvg
	 * @param pg
	 * @param iQ
	 * @param continuously
	 * @param k
	 * @param rankingStrategy
	 * @throws IOException
	 */
	public DurableTopkMatching(Graph lvg, PatternGraph pg, BitSet iQ, boolean continuously, int k, int rankingStrategy)
			throws IOException {

		this.k = k;
		this.pg = pg;
		this.iQ = iQ;
		this.continuously = continuously;
		this.rankingStrategy = rankingStrategy;

		timeLimit = System.currentTimeMillis();

		// if TiPLa index is activated use the path filtering
		if (Config.TIPLA_ENABLED)
			filterCandidatesByPath(lvg, pg, iQ);
		else
			filterCandidates(lvg, pg, iQ);

		// threshold initialization
		initializeThreshold();

		int pn_id;
		Set<Node> c;
		Map<Integer, Set<Node>> initC;
		TreeMap<Integer, Set<Node>> tree;
		NavigableMap<Integer, Set<Node>> submap;

		while (threshold > 1) {

			initC = new HashMap<>();

			for (Entry<Integer, TreeMap<Integer, Set<Node>>> entry : Rank.entrySet()) {
				pn_id = entry.getKey();
				tree = entry.getValue();

				c = new HashSet<>();
				initC.put(pn_id, c);

				submap = tree.subMap(tree.ceilingKey(threshold), true, tree.lastKey(), true);

				for (Entry<Integer, Set<Node>> en : submap.entrySet())
					c.addAll(en.getValue());

				// store the smaller candidate size
				if (canDSize > c.size())
					canDSize = c.size();
			}

			if (Config.DEBUG)
				System.out.print("Threshold: " + threshold + "\tCan_size: " + initC.get(0).size());

			sizeOfRank++;

			initC = DUALSIM(initC);

			try {
				recursionsPerTheta = 0;
				searchPattern(initC, 0);

				if (Config.DEBUG)
					System.out.print("\tRecursions: " + recursionsPerTheta + "\n");
			} catch (Exception e) {
				System.out.println("\nTerminated Message: " + e.getMessage());

				// in case we found the to-k solution
				if (isTerminated && e.getMessage().contains("found"))
					break;
			}

			// get new threshold
			if (rankingStrategy == Config.ADAPTIVE_RANKING)
				threshold = getAdaptiveThreshold();
			else if (rankingStrategy == Config.MAX_RANKING)
				threshold = getMaxThreshold();

			// top-k heap is full & shortest duration >= current threshold
			// there are two cases now
			if (topkMatches.size() == k && topkMatches.peek().getDuration() >= threshold) {
				// either there is not any match with duration >
				// topkMatches.peek().getDuration()
				if (isTerminated) {
					break;
				} else {
					// or it may exist so terminate in the next smaller
					// threshold
					isTerminated = true;
				}
			}
		}

		// write matches
		writeTopMatches();
	}

	/**
	 * Threshold initialization depending on duration ordering
	 * 
	 * @throws IOException
	 */
	private void initializeThreshold() throws IOException {
		TreeMap<Integer, Set<Node>> ranking;
		int cand;

		// for each pattern node
		for (PatternNode p : pg.getNodes()) {

			ranking = Rank.get(p.getID());

			// if ranking is empty then no matches
			if (ranking.isEmpty()) {
				threshold = -1;

				// write no matches
				writeTopMatches();
				return;
			}

			// max & adaptive
			if (rankingStrategy != Config.ZERO_RANKING) {
				cand = 0;

				// from highest key to lowest in ranking
				for (int th : ranking.descendingKeySet()) {
					cand += ranking.get(th).size();

					// there should be at least k candidates until th
					if (cand >= Config.K && threshold > th) {
						threshold = th;
						break;
					}
				}
			}
		}

		// max & adaptive ranking
		if (rankingStrategy != Config.ZERO_RANKING) {
			matchesFound = new HashSet<>();
			durationMaxRanking = new HashSet<>();

			// if there are not k candidate nodes
			if (threshold > Config.MAXIMUM_INTERVAL) {
				threshold = -1;

				// write no matches
				writeTopMatches();
				return;
			}

			// store which threshold has been chosen
			durationMaxRanking.add(threshold);

			minimumCheckedTheta = threshold;
		} else { // zero ranking
			threshold = 2;
		}
	}

	/**
	 * Compute the next threshold based on adaptive ranking
	 * 
	 * @return
	 */
	private int getAdaptiveThreshold() {

		int threshold = minimumCheckedTheta;

		threshold -= Math.round(Config.ADAPTIVE_THETA * threshold);

		if (threshold < 2) {
			if (minimumCheckedTheta == 2)
				return 1;

			minimumCheckedTheta = 2;
			return 2;
		}

		// store threshold that have been analyzed
		durationMaxRanking.add(threshold);

		minimumCheckedTheta = threshold;

		return threshold;
	}

	/**
	 * Get next threshold based on max ranking
	 * 
	 * @return
	 */
	private int getMaxThreshold() {
		int sc;
		TreeMap<Integer, Set<Node>> ranking;
		threshold = minimumCheckedTheta;

		for (PatternNode p : pg.getNodes()) {
			ranking = Rank.get(p.getID());

			if (ranking.floorKey(threshold) == null)
				continue;

			// get the lower highest threshold
			sc = ranking.floorKey(threshold);

			if (threshold > sc)
				threshold = sc;
		}

		// if threshold has been already checked
		while (durationMaxRanking.contains(threshold)) {
			threshold--;
		}

		// store threshold that have been analyzed
		durationMaxRanking.add(threshold);

		// store the minimum checked threshold
		minimumCheckedTheta = threshold;

		return threshold;
	}

	/**
	 * Dual-based isomorphism algorithm
	 * 
	 * @param c
	 * @param depth
	 * @throws Exception
	 */
	private void searchPattern(Map<Integer, Set<Node>> c, int depth) throws Exception {
		// increase the counters for recursions
		totalRecursions++;
		recursionsPerTheta++;

		if (System.currentTimeMillis() > (timeLimit + Config.TIME_LIMIT * 1000)) {
			throw new Exception("Reach time limit");
		} else if (topkMatches.size() == k && topkMatches.peek().getDuration() >= minimumCheckedTheta && isTerminated) {
			throw new Exception("Top-k found");
		} else if (depth == pg.size() && c.size() != 0) {
			computeMatchTime(c);
		} else if (!c.isEmpty()) {

			for (Node u : c.get(depth)) {

				if (!contains(c, u, depth)) {
					Map<Integer, Set<Node>> cCopy = new HashMap<Integer, Set<Node>>(c.size());

					// copy
					for (Entry<Integer, Set<Node>> entry : c.entrySet())
						cCopy.put(entry.getKey(), new HashSet<>(entry.getValue()));

					// set cCopy(depth) = u
					cCopy.get(depth).clear();
					cCopy.get(depth).add(u);

					searchPattern(refine(cCopy), depth + 1);
				}
			}
		}
	}

	/**
	 * Compute for each match the minimum time
	 * 
	 * @param match
	 * @throws Exception
	 */
	private void computeMatchTime(Map<Integer, Set<Node>> match) throws Exception {
		BitSet inter = (BitSet) iQ.clone();
		Node src, trg;
		String matchSign = null;
		int[] signAr = null;
		int duration = -1;

		if (rankingStrategy != Config.ZERO_RANKING)
			signAr = new int[match.size()];

		// check the edges
		for (PatternNode pn : pg.getNodes()) {

			// get the node that have same label as pn
			src = match.get(pn.getID()).iterator().next();

			if (rankingStrategy != Config.ZERO_RANKING)
				signAr[pn.getID()] = src.getID();

			if (Config.LABEL_CHANGE)
				// intersect labels lifespan
				inter.and(src.getLabel(pn.getLabel()));

			// get adjacency of pn
			for (PatternNode child : pn.getAdjacency()) {

				// get the node that have the same label as child
				trg = match.get(child.getID()).iterator().next();

				inter.and(src.getEdge(trg).getLifetime());

				if (continuously) {
					BitSet shifted = (BitSet) inter.clone();
					int count = 0;

					while (!shifted.isEmpty()) {
						shifted.and(shifted.get(1, shifted.length()));
						count++;
					}

					duration = count;

				}
			}
		}

		if (!continuously)
			duration = inter.cardinality();

		// if match has already been found or duration is zero
		if (rankingStrategy != Config.ZERO_RANKING && matchesFound.contains((matchSign = Arrays.toString(signAr))))
			return;

		int minDuration;

		if (rankingStrategy == Config.ZERO_RANKING) { // ranking strategy
			// is zero

			if (topkMatches.size() < k) {

				// add the match
				topkMatches.offer(new Match(duration, inter, match));

			} else if (topkMatches.size() == k) {

				// if we have found k matches and the new match has higher
				// duration
				if (duration > (minDuration = topkMatches.peek().getDuration())) {

					// remove match with the min duration
					topkMatches.remove();

					// add the match
					topkMatches.offer(new Match(duration, inter, match));

					// get shortest duration in the heap
					minDuration = topkMatches.peek().getDuration();
				}

				// update threshold
				threshold = minDuration + 1;
			}
		} else { // if ranking strategy is max or adaptive

			// add the sign
			matchesFound.add(matchSign);

			if (topkMatches.size() < k) {

				// add the match
				topkMatches.offer(new Match(duration, inter, match));

			} else if (topkMatches.size() == k) {

				// if we have found k matches and the new match has higher
				// duration
				if (duration > (minDuration = topkMatches.peek().getDuration())) {

					// remove match with the min duration as and its sign
					topkMatches.poll();

					// add the match
					topkMatches.offer(new Match(duration, inter, match));

					// update threshold
					if (threshold < (minDuration = topkMatches.peek().getDuration())) {
						threshold = minDuration + 1;

						// if threshold minDuration + 1 has already been checked
						if (durationMaxRanking.contains(threshold)) {
							// top-k solution has been found
							isTerminated = true;
							throw new Exception("Top-k found");
						}

						durationMaxRanking.add(threshold);
					} else if (threshold == minDuration) {
						threshold++;

						// if threshold ++ has already been checked
						if (durationMaxRanking.contains(threshold)) {
							// top-k solution has been found
							isTerminated = true;
							throw new Exception("Top-k found");
						}

						durationMaxRanking.add(threshold);

					}

					durationMaxRanking.add(threshold);

				} else if (duration == minDuration) {

					if (duration > threshold) {

						threshold = duration + 1;

						// if threshold ++ has already been checked
						if (durationMaxRanking.contains(threshold)) {
							// top-k solution has been found
							isTerminated = true;
							throw new Exception("Top-k found");
						}

						// inform structure that this threshold has been chosen
						durationMaxRanking.add(threshold);
					} else if (duration == threshold) {

						threshold++;

						// if threshold ++ has already been checked
						if (durationMaxRanking.contains(threshold)) {
							// top-k solution has been found
							isTerminated = true;
							throw new Exception("Top-k found");
						}

						durationMaxRanking.add(threshold);
					}
				}
			}
		}
	}

	/**
	 * Dual Simulation Algorithm
	 * 
	 * @param c
	 * @return
	 */
	private Map<Integer, Set<Node>> DUALSIM(Map<Integer, Set<Node>> c) {
		// variables
		boolean changed = true;
		Node phiNode;
		List<Node> phiTemp;
		Set<Node> newC;
		Set<Node> phiqNode;

		while (changed) {
			changed = false;

			// for each node of pattern graph
			for (PatternNode qNode : pg.getNodes()) {
				phiqNode = c.get(qNode.getID());

				// for each node of pattern graph get the adjacency
				for (PatternNode qChild : qNode.getAdjacency()) {

					// newPhi corresponds to phi(qChild). This update
					// will ensure that phi(qChild) will contain only
					// nodes which have a parent in phi(qNode)
					newC = new HashSet<Node>();

					// for all phi(qNode)
					for (Iterator<Node> i = phiqNode.iterator(); i.hasNext();) {
						// phiTemp corresponds to the children of
						// phiNode which are contained in phi(qChild).
						// This checks both if phiNode has children in
						// phi(qChild) (of which it must have at least one)
						// and also builds newPhi to contain only those
						// nodes in phi(qChild) which also have a parent
						// in phi(qNode)
						phiNode = i.next();

						phiTemp = timeJoin(phiNode, qNode, qChild, c);

						if (phiTemp.isEmpty()) {
							// remove phiNode from phi(qNode)
							i.remove();

							// if phi(u) is empty then return an empty set
							if (phiqNode.isEmpty())
								return Collections.emptyMap();

							changed = true;
						} else
							// F'(u') = F'(u') UNION F_{v}(u')
							newC.addAll(phiTemp);
					}

					// if any phi(i) is empty, then there is no
					// isomorphic subgraph.
					if (newC.isEmpty())
						return Collections.emptyMap();

					// if F'(i') is smaller than F(u')
					if (newC.size() < c.get(qChild.getID()).size())
						changed = true;

					// every node in phi(qChild) must have at least one parent
					// in phi(qNode)
					// newPhi.retainAll(phi.get(qChild.getID()));
					c.put(qChild.getID(), newC);
				}
			}
		}
		return c;
	}

	/**
	 * Refinement procedure
	 * 
	 * @param c
	 * @return
	 */
	private Map<Integer, Set<Node>> refine(Map<Integer, Set<Node>> c) {
		Node phiNode;
		List<Node> phiTemp;
		Set<Node> c_;

		for (PatternNode qNode : pg.getNodes()) {

			for (PatternNode qChild : qNode.getAdjacency()) {
				c_ = new HashSet<Node>();

				for (Iterator<Node> j = c.get(qNode.getID()).iterator(); j.hasNext();) {
					phiNode = j.next();
					phiTemp = timeJoin(phiNode, qNode, qChild, c);

					if (phiTemp.isEmpty())
						j.remove();
					else
						c_.addAll(phiTemp);
				}

				if (c_.isEmpty())
					return Collections.emptyMap();

				// newPhi.retainAll(phi.get(qChild.getID()));
				c.put(qChild.getID(), c_);
			}
		}
		return c;
	}

	/**
	 * Intersection between Nodes that are live during the interval iQ
	 * 
	 * @param n
	 * @param p
	 * @param chil
	 * @return
	 */
	private List<Node> timeJoin(Node n, PatternNode p, PatternNode chil, Map<Integer, Set<Node>> phi) {
		List<Node> intersection = new ArrayList<Node>();
		BitSet inter, labelLife = (BitSet) iQ.clone();

		if (Config.LABEL_CHANGE) {
			labelLife.and(n.getLabel(p.getLabel()));

			if (continuously) {
				BitSet shifted = (BitSet) labelLife.clone();
				int count = 0;

				while (!shifted.isEmpty()) {
					shifted.and(shifted.get(1, shifted.length()));
					count++;
				}

				if (count < threshold)
					return intersection;

			} else if (labelLife.cardinality() < threshold)
				return intersection;
		}

		if (n.getAdjacency().size() < phi.get(chil.getID()).size()) {
			for (Edge e : n.getAdjacency()) {
				if (phi.get(chil.getID()).contains(e.getTarget())) {

					inter = (BitSet) labelLife.clone();

					// intersection between edge lifespan and interval I
					inter.and(e.getLifetime());

					if (Config.LABEL_CHANGE)
						inter.and(e.getTarget().getLabel(chil.getLabel()));

					if (continuously) {
						BitSet shifted = (BitSet) inter.clone();
						int count = 0;

						while (!shifted.isEmpty()) {
							shifted.and(shifted.get(1, shifted.length()));
							count++;
						}

						if (count < threshold)
							continue;

					} else if (inter.cardinality() < threshold) {
						// check if target is pruned or it is not alive during
						// interval
						continue;
					}

					intersection.add(e.getTarget());
				}
			}
		} else {
			Edge e;

			for (Node ngb : phi.get(chil.getID())) {

				// if n has neighbor ngb
				if ((e = n.getEdge(ngb)) != null) {

					inter = (BitSet) labelLife.clone();

					// intersection between edge lifespan and interval I
					inter.and(e.getLifetime());

					if (Config.LABEL_CHANGE)
						inter.and(ngb.getLabel(chil.getLabel()));

					if (continuously) {
						BitSet shifted = (BitSet) inter.clone();
						int count = 0;

						while (!shifted.isEmpty()) {
							shifted.and(shifted.get(1, shifted.length()));
							count++;
						}

						if (count < threshold)
							continue;

					} else if (inter.cardinality() < threshold) {
						// check if target is pruned or it is not alive
						// during interval
						continue;
					}

					intersection.add(ngb);
				}
			}
		}

		return intersection;
	}

	/**
	 * Check if node u is contained in at least one candidate set
	 *
	 * @param phi
	 * @param u
	 * @param depth
	 * @return
	 */
	private boolean contains(Map<Integer, Set<Node>> phi, Node u, int depth) {
		for (int i = 0; i < depth; i++)
			if (phi.get(i).contains(u))
				return true;

		return false;
	}

	/**
	 * Generates candidates per pattern node using TiLa or TiNLa or CTiNLa
	 * 
	 * @param lvg
	 * @param pg
	 * @param iQ
	 */
	private void filterCandidates(Graph lvg, PatternGraph pg, BitSet iQ) {
		Map<Integer, Set<Node>> phi = new HashMap<Integer, Set<Node>>(pg.size());

		// initialize
		for (PatternNode pn : pg.getNodes()) {
			phi.put(pn.getID(), new HashSet<Node>());
			Rank.put(pn.getID(), new TreeMap<>());
		}

		// create TiNLa & CTiNLa indexes
		if (Config.TINLA_ENABLED || Config.CTINLA_ENABLED)
			pg.createTimeNeighborIndex();

		boolean found;
		BitSet lifespan;
		int label;
		Set<Node> pnode_candidates, current_candidates, candidates;
		Map<Integer, Set<Node>> rankingBasedOnlifespanScore;

		// store for a label the candidate nodes
		Map<Integer, Set<Node>> labelCandidates = new HashMap<>();
		Node n;

		for (PatternNode pn : pg.getNodes()) {
			pnode_candidates = phi.get(pn.getID());

			// get pattern's node label
			label = pn.getLabel();

			// if label exist then retrieve its candidates for all iQ
			if ((candidates = labelCandidates.get(label)) != null)
				pnode_candidates.addAll(candidates);
			else {
				// for each time instant get the candidates and add them in one
				// set
				for (Iterator<Integer> it = iQ.stream().iterator(); it.hasNext();)
					pnode_candidates.addAll(lvg.getTiLaNodes(it.next(), label));

				// candidates for label in iQ
				labelCandidates.put(label, new HashSet<>(pnode_candidates));
			}

			rankingBasedOnlifespanScore = Rank.get(pn.getID());

			for (Iterator<Node> it = pnode_candidates.iterator(); it.hasNext();) {
				n = it.next();

				lifespan = (BitSet) iQ.clone();
				lifespan.and(n.getLabel(label));

				// if TiNLa is enabled
				if (Config.TINLA_ENABLED) {
					found = true;

					// for each r
					for (int r = 0; r < Config.TINLA_R; r++) {

						for (int l : pn.getTiNLa(r)) {
							// if there is not a neighbor with that label or the
							// lifespan is empty
							// remove it
							if ((lifespan = n.getTiNLa(r, l, lifespan)) == null || lifespan.isEmpty()) {
								found = false;
								it.remove();
								break;
							}
						}

						if (!found)
							break;
					}

					if (!found)
						continue;
				} else if (Config.CTINLA_ENABLED) {
					found = true;

					// for each r
					for (int r = 0; r < Config.CTINLA_R; r++) {

						for (Entry<Integer, Integer> l : pn.getCTiNLa(r).entrySet()) {

							// if there is not a neighbor with that label
							// remove it
							if ((lifespan = n.getCTiNLa(r, l.getKey(), l.getValue(), lifespan)) == null
									|| lifespan.isEmpty()) {
								found = false;
								it.remove();
								break;
							}
						}

						if (!found)
							break;
					}

					if (!found)
						continue;
				} else if (lifespan.isEmpty()) { // check if Node n lifespan
													// does not contain any bit
					it.remove();
					continue;
				}

				int sc;

				// remove node from candidates since with score 1 it is not
				// durable
				if ((sc = lifespan.cardinality()) == 1)
					it.remove();
				else {
					if ((current_candidates = rankingBasedOnlifespanScore.get(sc)) == null) {
						current_candidates = new HashSet<>();
						rankingBasedOnlifespanScore.put(sc, current_candidates);
					}
					// add candidate node
					current_candidates.add(n);
				}
			}
		}
	}

	/**
	 * Generates candidates per pattern node using TiPLa
	 * 
	 * @param lvg
	 * @param pg
	 * @param iQ
	 */
	private void filterCandidatesByPath(Graph lvg, PatternGraph pg, BitSet iQ) {

		// create candidates set pattern node--> set of lvg nodes
		Map<Integer, Set<Node>> candidates = new HashMap<Integer, Set<Node>>(pg.size());

		// support variables
		Set<Node> currentCandidates = null;
		nodeScore sc;

		// ranking for each pattern node
		Map<PatternNode, Map<Integer, nodeScore>> score = new HashMap<>();

		// create pattern path index
		pg.createPathIndex();

		// initialize
		for (PatternNode pn : pg.getNodes()) {
			candidates.put(pn.getID(), new HashSet<Node>());
			Rank.put(pn.getID(), new TreeMap<>());
		}

		// for each pattern node
		for (PatternNode pn : pg.getNodes()) {
			// initiate score structure
			score.put(pn, new HashMap<>());

			// for each iQ true bit
			for (Iterator<Integer> it = iQ.stream().iterator(); it.hasNext();) {
				int t = it.next();
				Set<Node> intersection = null;

				// for all pattern node pn paths
				for (String path : pg.getTiPLa(pn.getID())) {

					// get the candidates from the time path index
					if ((currentCandidates = lvg.getTiPLa().get(t).get(path)) != null) {

						if (intersection == null) {
							intersection = new HashSet<>();
							intersection.addAll(currentCandidates);
						} else
							intersection.retainAll(currentCandidates);
					} else
						break;

					if (intersection.isEmpty())
						break;
				}

				if (intersection != null) {
					for (Node n : intersection) {

						if ((sc = score.get(pn).get(n.getID())) == null) {
							sc = new nodeScore();
							score.get(pn).put(n.getID(), sc);
						} else
							sc.score++;
					}
				}
			}
		}

		// support variables
		int durScore;
		PatternNode pn;
		TreeMap<Integer, Set<Node>> patternNodeRank;

		for (Entry<PatternNode, Map<Integer, nodeScore>> entry : score.entrySet()) {
			pn = entry.getKey();
			patternNodeRank = Rank.get(pn.getID());

			// for each candidate node
			for (Entry<Integer, nodeScore> entry1 : entry.getValue().entrySet()) {
				durScore = entry1.getValue().score;

				// a node must have duration > 1
				if (durScore == 1)
					continue;

				if ((currentCandidates = patternNodeRank.get(durScore)) == null) {
					currentCandidates = new HashSet<>();
					patternNodeRank.put(durScore, currentCandidates);
				}

				currentCandidates.add(lvg.getNode(entry1.getKey()));
			}
		}
	}

	/**
	 * Write Matches
	 * 
	 * @throws IOException
	 */
	private void writeTopMatches() throws IOException {
		totalTime = (System.currentTimeMillis() - timeLimit);

		String outputPath = Config.PATH_OUTPUT + "topk_pq=" + pg.getID() + "_";

		if (continuously)
			outputPath += "cont_";

		if (Config.TINLA_ENABLED)
			outputPath += "tinla(" + Config.TINLA_R + ")_";
		else if (Config.CTINLA_ENABLED)
			outputPath += "ctinla(" + Config.CTINLA_R + ")_";
		else if (Config.TIPLA_ENABLED)
			outputPath += "tipla_";
		else
			outputPath += "tila_";

		if (rankingStrategy == Config.ADAPTIVE_RANKING)
			outputPath += "r=a";
		else if (rankingStrategy == Config.MAX_RANKING)
			outputPath += "r=m";
		else if (rankingStrategy == Config.ZERO_RANKING)
			outputPath += "r=z";

		FileWriter w = new FileWriter(outputPath);
		w.write("Top-" + k + " matches\n");
		w.write("Pattern Graph: " + pg.getID() + "\n");
		w.write("Total matches: " + topkMatches.size() + "\n");
		w.write("Recursive Time: " + totalTime + " (ms)\n");
		w.write("sizeOfRank: " + sizeOfRank + "\n");
		w.write("Total Recursions: " + totalRecursions + "\n");
		w.write("-------------------\n");

		// no matches found
		if (threshold == -1) {
			w.write("No matches");
			w.close();
			return;
		}

		// stores the result
		String result = "";
		BitSet shifted;
		int count;

		// reverse minHeap to maxHeap in order to ouput from high to low
		PriorityQueue<Match> topkMatchesR = new PriorityQueue<Match>(11, new MatchComparator(true));

		while (!topkMatches.isEmpty())
			topkMatchesR.offer(topkMatches.poll());

		while (!topkMatchesR.isEmpty()) {
			Match mI = topkMatchesR.poll();

			result += "------ Match ------\n";

			if (continuously) {
				shifted = (BitSet) mI.getLifespan().clone();
				count = 0;

				while (!shifted.isEmpty()) {
					shifted.and(shifted.get(1, shifted.length()));
					count++;
				}

				result += "Duration : " + count + "\n";
			} else
				result += "Duration : " + mI.getLifespan().cardinality() + "\n";

			result += "Lifetime : " + mI.getLifespan() + "\n";
			result += "------ Nodes ------\n";

			for (Entry<Integer, Set<Node>> mg : mI.getMatch().entrySet()) {
				// pattern node id
				result += "pg_id: " + mg.getKey() + "\n";

				for (Node n : mg.getValue())
					// graph node id
					result += "g_id: " + n.getID() + "\n";
			}

			// write the edges
			for (PatternNode pn : pg.getNodes()) {

				// for each adjacent node
				for (PatternNode trg : pn.getAdjacency()) {

					for (Node n : mI.getMatch().get(pn.getID())) {

						for (Edge e : n.getAdjacency()) {

							if (mI.getMatch().get(trg.getID()).contains(e.getTarget())) {

								if (Config.PATH_DATASET.contains("dblp"))
									result += LoaderDBLP.getAuthors().get(n.getID()) + ": ";

								result += "(" + pn.getID() + ") ---> (" + trg.getID() + ")";

								if (Config.PATH_DATASET.contains("dblp"))
									result += " " + LoaderDBLP.getAuthors().get(e.getTarget().getID());

								result += "\n";
							}
						}
					}
				}
			}

			result += "-------------------\n";
		}

		w.write(result);
		w.close();
	}

	/**
	 * Support Class for filter candidates by path
	 * 
	 * @author ksemer
	 */
	class nodeScore {
		public int score;

		public nodeScore() {
			score = 1;
		}
	}
}
