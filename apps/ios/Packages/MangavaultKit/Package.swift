// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "MangavaultKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MangavaultKit", targets: ["MangavaultKit"]),
    ],
    targets: [
        .target(name: "MangavaultKit"),
        .testTarget(name: "MangavaultKitTests", dependencies: ["MangavaultKit"]),
    ]
)
