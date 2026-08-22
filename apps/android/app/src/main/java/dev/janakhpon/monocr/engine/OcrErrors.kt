package dev.janakhpon.monocr.engine

/**
 * The loaded model does not describe the same thing this build decodes with.
 *
 * The failure this names is not a crash. A 277-class graph read through a
 * 315-character table yields well-formed Mon text that is wrong, with no exception
 * and no lookup miss, because every decodable index is in range of the larger table.
 * There is no symptom to notice, which is why it has to be refused at load rather
 * than detected at read.
 */
class ModelContractException(message: String) : IllegalStateException(message)

/**
 * One line failed in the model runtime.
 *
 * Distinct from a blank line on purpose. This used to be caught and turned into an
 * empty string, so a device whose NNAPI driver aborted on every line produced a
 * blank page that looked like a page with no text on it.
 */
class LineInferenceException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
