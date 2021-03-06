package com.yg.graph;

import java.util.ArrayList;
import java.util.List;
/**
 * Node in a graph
 * @author Yaroslava Girilishena
 *
 * @param <T> generic
 */
public class Node<T> {
	
	private String id; // identifier
	private T data; // any data
	private int weight; // weight of node
	
    private List<Node<T>> children;
    private Node<T> parent;

    /**
     * Constructor
     * @param data
     * @param weight
     */
    public Node(T data, int weight) {
        this.data = data;
        this.weight = weight;
        this.children = new ArrayList<Node<T>>();
    }

    /**
     * Constructor 
     * @param node
     */
    public Node(Node<T> node) {
        this.data = node.getData();
        this.weight = node.getWeight();
        children = new ArrayList<Node<T>>();
    }

    /**
     * Add child to a node
     * @param child
     */
    public void addChild(Node<T> child) {
        child.setParent(this);
        children.add(child);
    }

    /**
     * Add child at a specified index
     * @param index
     * @param child
     */
    public void addChildAt(int index, Node<T> child) {
        child.setParent(this);
        this.children.add(index, child);
    }

    /**
     * Add list of children to a node
     * @param children
     */
    public void setChildren(List<Node<T>> children) {
        for (Node<T> child : children)
            child.setParent(this);

        this.children = children;
    }

    /**
     * Remove given child of the node
     * @param childToBeDeleted the child node to remove.
     * @return <code>true</code> if the given node was a child of this node and was deleted,
     * <code>false</code> otherwise.
     */
    public boolean removeChild(Node<T> childToBeDeleted) {
        List<Node<T>> list = getChildren();
        return list.remove(childToBeDeleted);
    }
    
    /**
    * Remove child at given index
    * @param index The index at which the child has to be removed.
    * @return the removed node.
    */
    public Node<T> removeChildAt(int index) {
    	return children.remove(index);
    }
   
    /**
     * Remove all children
     */
    public void removeChildren() {
        this.children.clear();
    }

    public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}
	
    public T getData() {
        return this.data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public int getWeight() {
		return weight;
	}

	public void setWeight(int weight) {
		this.weight = weight;
	}
	
    public Node<T> getParent() {
        return this.parent;
    }

    public void setParent(Node<T> parent) {
        this.parent = parent;
    }

    public List<Node<T>> getChildren() {
        return this.children;
    }

    public Node<T> getChildAt(int index) {
        return children.get(index);
    }

    public boolean equals(Object obj) {
        if (null == obj)
            return false;

        if (obj instanceof Node) {
            if (((Node<?>) obj).getData().equals(this.data))
                return true;
        }

        return false;
    }

    public String toString() {
        return this.data.toString();
    }

}
