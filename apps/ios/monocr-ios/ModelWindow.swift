import Foundation

/**
 The input window the shipped graph was traced at.

 One definition, because four things have to agree on it: the preprocessor that
 resizes to it, the tiler that decides what fits inside it, the inference call
 that declares the tensor shape, and the load-time contract check that refuses a
 graph traced at anything else. They used to agree by three separate literals.
 */
nonisolated enum ModelWindow {
    static let height = 160
    static let width = 1024
}
