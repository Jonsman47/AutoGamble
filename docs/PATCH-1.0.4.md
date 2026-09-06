# AutoGamble 1.0.4

Runtime prefix length is independently sampled uniformly from the configured inclusive range (default
1–3), then each lowercase letter is independently sampled. Persistent injected RNG is reused. Exact
prefix repeats within a selection cycle are resampled; candidate selection retains its separate RNG.
Empty results retry after 200ms, request timeout remains 3 seconds, maximum 10 requests. Failed complete
searches return to the configured normal payment delay. Existing known-paid history reset policy remains.
Config fields minimumPrefixLength=1 and maximumPrefixLength=3 are added without replacing prior values.
GUI: Auto Pay → Prefix Length, with strict whole-number/range validation and draft Save/Cancel behavior.
Status includes range, last length/prefix and active attempt count. Dry Run, numeric filtering and failed
blacklist policies are unchanged. Minecraft 26.2, Java 25 and Fabric Loader 0.19.3 remain unchanged.

183 tests passed, including all 165 existing tests. Gradle test and build succeeded. No live DonutSMP
connection was used. Existing compatibility-only fixed-prefix constructors are retained for old tests;
the runtime always uses the new configurable constructor. Release JAR: build/libs/autogamble-1.0.4.jar.
