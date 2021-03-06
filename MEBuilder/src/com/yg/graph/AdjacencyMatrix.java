package com.yg.graph;

/**
 * Adjacency matrix class
 * @author Yaroslava Girilishena
 *
 */
public class AdjacencyMatrix {
	
	public int[][] items; // elements in matrix
	private int rows; // number of rows
	private int cols; // number of columns
	
	/**
	 * Constructor of a square matrix
	 * @param size - size of a square matrix
	 */
	public AdjacencyMatrix(int size) {
		this(size, size);
	}
	
	/**
	 * Constructor
	 * @param rows - number of rows
	 * @param cols - number of columns
	 */
	public AdjacencyMatrix(int rows, int cols) {
		this.items = new int[rows][cols];
		this.rows = rows;
		this.cols = cols;
		initMatrix();
	}

	/**
	 * Init matrix with another matrix
	 * @param matrix
	 */
	public AdjacencyMatrix(AdjacencyMatrix matrix) {
		this.rows = matrix.getRows();
		this.cols = matrix.getCols();
		
		this.items = new int[matrix.getRows()][matrix.getCols()];
		
		// Copy elements
		for (int i=0; i < matrix.getRows(); i++) {
			for (int j=0; j < matrix.getCols(); j++) {
				this.items[i][j] = matrix.items[i][j];
			}
		}
	}
	
	/**
	 * Initialize the matrix
	 */
	public void initMatrix() {
		if (this.items == null || this.items.length == 0) {
			return;
		}
		for (int i=0; i<rows; i++) {
    		for (int j=0; j<cols; j++) {
    			this.items[i][j] = 0;
    		}
    	}
	}
	
	/**
	 * Create the symmetric matrix 
	 */
	public void makeSymmetricMatrix() {
		for (int i=0; i<rows; i++) {
			for (int j=0; j<cols; j++) {
				// Diagonal should be filled with 0's
				if (i == j) {
					items[i][j] = 0;
				}
				if (j > i) {
					if (items[i][j] > 0) {
						items[j][i] = items[i][j];
						items[i][j] = 0;
					} 
				}
			}
		}
	}
	
	/**
	 * Swipe two rows
	 * @param row1
	 * @param row2
	 */
	public void swipeRows(int row1, int row2) {
		int[] temp = new int[cols];
		
		for (int j=0; j<cols; j++) {
			temp[j] = items[row1][j];
		}
		
		for (int j=0; j<cols; j++) {
			items[row1][j] = items[row2][j];
			items[row2][j] = temp[j];
		}
	}
	
	/**
	 * Swipe two columns
	 * @param col1
	 * @param col2
	 */
	public void swipeCols(int col1, int col2) {
		int[] temp = new int[rows];
		
		for (int i=0; i<rows; i++) {
			temp[i] = items[i][col1];
		}
		
		for (int i=0; i<rows; i++) {
			items[i][col1] = items[i][col2];
			items[i][col2] = temp[i];
		}
	}
	
	/**
	 * Reset a column
	 * @param col - column index
	 */
	public void clearCol(int col) {
		if (col < 0 || col >= this.cols) {
			return;
		}
		for (int i=0; i<rows; i++) {
			items[i][col] = 0;
		}
	}
	
	/**
	 * Reset a row
	 * @param row - row index
	 */
	public void clearRow(int row) {
		if (row < 0 || row >= this.rows) {
			return;
		}
		for (int j=0; j<cols; j++) {
			items[row][j] = 0;
		}
	}
	
	/** 
	 * Copy another matrix
	 * @param matrix
	 */
	public void copyFrom(AdjacencyMatrix matrix) {
		if (this.rows != matrix.getRows() || this.cols != matrix.getCols()) {
			return;
		}
		for (int i=0; i < matrix.getRows(); i++) {
			for (int j=0; j < matrix.getCols(); j++) {
				this.items[i][j] = matrix.items[i][j];
			}
		}
	}
	
	/**
	 * Print out the matrix
	 */
	public String toString() {
		String res = "";
		
		for (int i=0; i<rows; i++) {
			for (int j=0; j<cols; j++) {
				res += items[i][j] + " ";
			}
			res += "\n";
		}
		return res;
	}

	/**
	 * Getters
	 */
	public int getRows() {
		return rows;
	}

	public int getCols() {
		return cols;
	}
	
}
