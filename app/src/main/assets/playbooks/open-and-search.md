---
id: open_and_search
name: Open App and Search
triggers:
  - "search"
  - "find"
  - "look up"
  - "look for"
---

When the user wants to search for something in an app:

1. **open_app** → call open_app(package_name="[app]") to open the app FIRST. Do NOT skip this step.
2. **get_screen_info** → see what's on screen after the app opens
3. **find_and_tap** → call find_and_tap(text="Search") to tap the search bar or icon
4. **input_text** → call input_text(text="[search query]") to type the query
5. **system_key** → call system_key(key="enter") to submit the search
6. **get_screen_info** → read the results
7. **finish** → call finish(summary="[describe what you found]")

If the user says "search [app] for [query]":
- app = the app name (YouTube, Chrome, Play Store, etc.)
- query = what to search for

IMPORTANT: Always open the app FIRST (step 1). Never type into the current screen without opening the target app.
