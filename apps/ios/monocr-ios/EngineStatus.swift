import Foundation

enum EngineStatus: Equatable {
    case loading
    case ready
    case error(String)
}
