package com.yg.graph;

import java.util.ArrayList;
import java.util.List;

import com.yg.graph.dijkstra.Edge;
import com.yg.graph.dijkstra.Vertex;

/**
 * This class represents a graph structure, 
 * does dfs search and gets all paths between any two nodes
 * 
 * @author Yaroslava Girilishena
 *
 */
public class Graph {
	
	private List<Vertex> vertices; // list of vertices
    private List<Edge> edges; // list of edges
    
    public List<List<Integer>> adj = new ArrayList<List<Integer>>(); // list of adjacent vertices for each vertex
    public List<List<Vertex>> paths = new ArrayList<List<Vertex>>();
    
	private int adjMat[][]; // adjacency matrix
    private StackX theStack; // stack for dfs
    
    /**
     * Constructor - empty
     */
    public Graph() {
    	vertices = new ArrayList<Vertex>();
    	initGraph();
    }
    
    /**
     * Constructor
     * @param vertices - list of vertices
     */
    public Graph(List<Vertex> vertices) {
        this.vertices = vertices;
        initGraph();
    }
    
    /**
     * Constructor
     * @param vertices - list of vertices
     * @param edges - list of edges
     */
    public Graph(List<Vertex> vertices, List<Edge> edges) {
        this.vertices = vertices;
        this.edges = edges;
    }
    
    /**
     * Init variables
     */
    public void initGraph() {
    	// Init stack
        theStack = new StackX();
        
    	// Init list of adjacent vertices
    	for (int i=0; i < this.vertices.size(); i++) {
    		adj.add(new ArrayList<Integer>());
    	}
    	// Init adjacency matrix
    	adjMat = new int[this.vertices.size()][this.vertices.size()];
    	for (int i=0; i<this.vertices.size(); i++) {
    		for (int j=0; j<this.vertices.size(); j++) {
    			adjMat[i][j] = 0;
        	}
    	}
    }
    
    /**
     * Add vertex
     * @param v - vertex
     */
    public void addVertex(Vertex v) {
    	this.vertices.add(v);
    }
    
    /**
     * Print out vertex
     * @param i - index of vertex
     */
    public void displayVertex(int i) { 
    	System.out.print(vertices.get(i).getData()); 
    }
    
    public List<Vertex> getVertices() {
        return vertices;
    }

    /**
     * Add edge
     * @param u - from vertex
     * @param v - to vertex
     */
    public void addNewEdge(int u, int v) {
    	// Add edge to adjacency matrix
    	adjMat[u][v] = 1; 
    	adjMat[v][u] = 1; 
    	// Add adjacent vertex v to list of adjacent vertices of u
    	adj.get(u).add(v);
    	adj.get(v).add(u);
    }
    
    public List<Edge> getEdges() {
        return edges;
    }
    
    /**
     * Get all possible paths between a source and a destination
     * @param s - index of source vertex
     * @param d - index of destination vertex
     * @return
     */
    public List<List<Vertex>> getAllPaths(int s, int d) {
        // Mark all the vertices as not visited
    	boolean[] visited = new boolean[d+1];
    	for (int i=0; i<d+1; i++) {
    		visited[i] = false;
    	}
    	
    	// Create a list to store paths
    	List<Vertex> newPath = new ArrayList<Vertex>();
    	for (int i=0; i<this.vertices.size(); i++) {
    		newPath.add(null);
    	}
    	int pathIndex = 0; // init path as empty
    	
    	// Call the recursive helper function to print all paths
    	getAllPathsUtil(s, d, visited, newPath, pathIndex);
    	
    	return paths;
    }
    
    public void getAllPathsUtil(int u, int d, boolean visited[], List<Vertex> path, int pathIndex) {
    	// Mark the current node and store it in path
    	visited[u] = true;
    	path.set(pathIndex, vertices.get(u));
    	pathIndex++;
    	
    	// If current vertex is same as destination, then print current patt
    	if (u == d) {
    		if (path.indexOf(null) != -1) {
    			paths.add(new ArrayList<Vertex>(path.subList(0, path.indexOf(null))));
    		} else {
    			paths.add(new ArrayList<Vertex>(path.subList(0, path.size())));
    		}
    	} else { // If current vertex is not destination
    		// Recur for all the vertices adjacent to current vertex
    		for (int i=0; i < adj.get(u).size(); i++) {
    	    	if (visited[adj.get(u).get(i)] == false) {
    				getAllPathsUtil(adj.get(u).get(i), d, visited, path, pathIndex);
    			}
    		}
    	}
    	
    	// Remove current vertex from path and mark it as unvisited
    	pathIndex--;
    	path.set(pathIndex, null);
    	visited[u] = false;
    }
    
    
    /**
     * Depth-first search
     * @return path
     */
    public List<Vertex> dfs() {
    	List<Vertex> path = new ArrayList<Vertex>();
    	
    	vertices.get(0).setWasVisited(true); // begin at vertex 0 // mark it
    	path.add(vertices.get(0)); // push to path
    	theStack.push(0); // push it
    	
    	while( !theStack.isEmpty() ) {
    		// get an unvisited vertex adjacent to stack top 
    		int v = getAdjUnvisitedVertex( theStack.peek() );
    		if (v == -1) { // if no such vertex
    			theStack.pop();
    		} else {
    			vertices.get(v).setWasVisited(true); // mark it
    	    	path.add(vertices.get(v)); // push to path
    			theStack.push(v); // push it
    		}
    	} // end while
    	
    	// stack is empty, so we’re done 
    	for (int j=0; j<vertices.size(); j++) {
    		vertices.get(j).setWasVisited(false); 
    	}
    	
    	return path;
    }
    	
    /**
     * Returns an unvisited vertex adjacent to v
     * @param v - index of vertex
     * @return
     */
    public int getAdjUnvisitedVertex(int v) {
    	for (int j=0; j<vertices.size(); j++) {
    		if(adjMat[v][j]==1 && !vertices.get(j).wasVisited()) {
    			return j;
    		}
    	}
    	return -1;
    } 
}