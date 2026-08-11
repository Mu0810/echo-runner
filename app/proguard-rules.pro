# Level JSON is parsed reflectively-free (org.json), so no keep rules are needed for it.
# Keep the enum valueOf paths used when restoring persisted progress.
-keepclassmembers enum com.echorunner.** { *; }
