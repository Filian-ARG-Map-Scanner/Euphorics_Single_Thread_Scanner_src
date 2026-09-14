# Euphorics_Single_Thread_Scanner_src
Src files for my re-write of Darrien's scanner (so all code is in the same place, to be superseded hopefully very soon)

MIT License, same as Darrien's tool. These are the source files used in my rewrite, you should be able to just replace the src dir in Darrien's tool with this new src dir and it should work.

ISSUES:
1) The reconnection script that should be reconnecting 3hours after restart often fails (it does disconnect correctly so should b no worries there) this seems to be a bit more involved as theres an error message about unable to connect to server please close game and try again, but sometimes it does work fine
2) The total scanned chunks adds in realtime, but also adds at the end of a scan the entire previous scan again (think it arises because we sum all chunks found in the csv files then add any scanning chunks in the session, should make it stop reading the csvs after init but i cant b bothered rn :Shruge: )
2.5) We also overcount any chunks that appear in two csvs, like at the overlap of when a scan stops/starts.  this overcount is corrected offline later, and the overcount in the overlay are usually small.
3) scanning starts immediately after starting a scan in the GUI, not when it reaches the first waypoint.  you can add many chunks to the scan that are not needed/duplicate if you are not already at the starting waypoint
4) bottleneck is the processing of blocks in a chunk, multi-threading would solve this (hopefully) and either movement or sending from server would be the next bottleneck
5) the scanner outputs to the same dir no matter the dim so u have to manually move all the scan data into different dirs depending on what dim ur scanning (a future centralized system should deal w/ this issue)
