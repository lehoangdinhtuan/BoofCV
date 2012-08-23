/*
 * Copyright (c) 2011-2012, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.geo.f;

import boofcv.alg.geo.AssociatedPair;
import boofcv.numerics.solver.RootFinderCompanion;
import boofcv.struct.FastQueue;
import georegression.struct.point.Point2D_F64;
import org.ejml.data.Complex64F;
import org.ejml.data.DenseMatrix64F;
import org.ejml.factory.DecompositionFactory;
import org.ejml.factory.SingularValueDecomposition;
import org.ejml.ops.CommonOps;
import org.ejml.ops.SingularOps;

import java.util.List;

/**
 * <p>
 * Finds the essential matrix given 5 or more corresponding points.  The approach is described
 * in details in [1] and works by linearlizing the problem then solving for the roots in a polynomial.  It
 * is considered one of the fastest and most stable solutions for this problem.
 * </p>
 *
 * <p>
 * THIS IMPLEMENTATION DOES NOT CONTAIN ALL THE OPTIMIZATIONS OUTLIED IN [1].  A full implementation is
 * quite involved.
 * </p>
 *
 * <p>
 * [1] David Nister "An Efficient Solution to the Five-Point Relative Pose Problem"
 * Pattern Analysis and Machine Intelligence, 2004
 * </p>
 *
 * @author Peter Abeles
 */
public class EssentialNister5 {

	// Linear system describing p'*E*q = 0
	DenseMatrix64F Q = new DenseMatrix64F(5,9);
	// contains the span of A
	DenseMatrix64F V = new DenseMatrix64F(9,9);
	// TODO Try using QR-Factorization as in the paper
	SingularValueDecomposition<DenseMatrix64F> svd = DecompositionFactory.svd(5,9,false,true,false);

	// where all the ugly equations go
	HelperNister5 helper = new HelperNister5();

	// the span containing E
	double []X = new double[9];
	double []Y = new double[9];
	double []Z = new double[9];
	double []W = new double[9];

	// unknowns for E = x*X + y*Y + z*Z + W
	double x,y,z;

	DenseMatrix64F A1 = new DenseMatrix64F(10,10);
	DenseMatrix64F A2 = new DenseMatrix64F(10,10);
	DenseMatrix64F C = new DenseMatrix64F(10,10);

	RootFinderCompanion polyRoot = new RootFinderCompanion();
	double coefs[] = new double[11];

	// found essential matrix
	FastQueue<DenseMatrix64F> solutions = new FastQueue<DenseMatrix64F>(11,DenseMatrix64F.class,false);

	public EssentialNister5() {
		for( int i = 0; i < solutions.data.length; i++ )
			solutions.data[i] = new DenseMatrix64F(3,3);
	}

	/**
	 * Computes the essential matrix from point correspondences.
	 *
	 * @param points List of points correspondences in normalized image coordinates.
	 * @return true for success or false if a fault has been detected
	 */
	public boolean process( List<AssociatedPair> points ) {
		if( points.size() < 5 )
			throw new IllegalArgumentException("A minimum of five points are required");

		// Computes the 4-vector span which contains E.  See equations 7-9
		computeSpan(points);

		// Construct a linear system based on the 10 constraint equations. See equations 5,6, and 10 .
		helper.setNullSpace(X,Y,Z,W);
		helper.setupA1(A1);
		helper.setupA2(A2);

		// instead of Gauss-Jordan elimination LU decomposition is used to solve the system
		CommonOps.solve(A1,A2,C);

		// construct the z-polynomial matrix.  Equations 11-14
		helper.setDeterminantVectors(C);
		helper.extractPolynomial(coefs);

		// Solve for the polynomial roots
		if( !polyRoot.process(coefs) )
			throw new RuntimeException("Something went really wrong for polynomial finder to fail");

		// compute solutions from real roots
		solutions.reset();
		for(Complex64F root : polyRoot.getRoots()) {
			if( !root.isReal() )
				continue;
			solveForXandY(z);

			DenseMatrix64F E = solutions.pop();
			E.data[0] = x*X[0] + y*Y[0] + z*Z[0] + W[0];
			E.data[1] = x*X[1] + y*Y[1] + z*Z[1] + W[1];
			E.data[2] = x*X[2] + y*Y[2] + z*Z[2] + W[2];
			E.data[3] = x*X[3] + y*Y[3] + z*Z[3] + W[3];
			E.data[4] = x*X[4] + y*Y[4] + z*Z[4] + W[4];
			E.data[5] = x*X[5] + y*Y[5] + z*Z[5] + W[5];
			E.data[6] = x*X[6] + y*Y[6] + z*Z[6] + W[6];
			E.data[7] = x*X[7] + y*Y[7] + z*Z[7] + W[7];
			E.data[8] = x*X[8] + y*Y[8] + z*Z[8] + W[8];
		}

		return true;
	}

	/**
	 * From the epipolar constraint p2^T*E*p1 = 0 construct a linear system
	 * and find its null space.
	 */
	private void computeSpan( List<AssociatedPair> points ) {

		Q.reshape(points.size(), 9);
		int index = 0;

		for( int i = 0; i < points.size(); i++ ) {
			AssociatedPair p = points.get(i);

			Point2D_F64 a = p.currLoc;
			Point2D_F64 b = p.keyLoc;

			// The points are assumed to be in homogeneous coordinates.  This means z = 1
			Q.data[index++] =  a.x*b.x;
			Q.data[index++] =  a.x*b.y;
			Q.data[index++] =  a.x;
			Q.data[index++] =  a.y*b.x;
			Q.data[index++] =  a.y*b.y;
			Q.data[index++] =  a.y;
			Q.data[index++] =      b.x;
			Q.data[index++] =      b.y;
			Q.data[index++] =  1;
		}

		if( !svd.decompose(Q) )
			throw new RuntimeException("SVD should never fail, probably bad input");

		svd.getV(V,true);

		// Order singular values if needed
		if( points.size() > 5 ) {
			double s[] = svd.getSingularValues();
			svd.getV(V,true);
			SingularOps.descendingOrder(null,false,s,svd.numberOfSingularValues(),V,true);
		}

		// extract the span of solutions for E
		for( int i = 0; i < 9; i++ ) {
			X[i] = V.unsafe_get(5,i);
			Y[i] = V.unsafe_get(6,i);
			Z[i] = V.unsafe_get(7,i);
			W[i] = V.unsafe_get(8,i);
		}
	}

	/**
	 * Once z is known then x and y can be solved for using the B matrix
	 */
	private void solveForXandY( double z ) {
		this.z = z;

		// solve for x and y using the first two rows of B
		double B11 = helper.K0*z*z*z + helper.K1*z*z + helper.K2*z + helper.K3;
		double B12 = helper.K4*z*z*z + helper.K5*z*z + helper.K6*z + helper.K7;
		double B13 = helper.K8*z*z*z*z + helper.K9*z*z*z + helper.K10*z*z + helper.K11*z + helper.K12;

		double B21 = helper.L0*z*z*z + helper.L1*z*z + helper.L2*z + helper.L3;
		double B22 = helper.L4*z*z*z + helper.L5*z*z + helper.L6*z + helper.L7;
		double B23 = helper.L8*z*z*z*z + helper.L9*z*z*z + helper.L10*z*z + helper.L11*z + helper.L12;

		y = (B13*B21 - B11*B23)/(B22*B11 - B12*B21);
		x = -(y*B12 + B13)/B11;
	}

	public List<DenseMatrix64F> getSolutions() {
		return solutions.toList();
	}
}
