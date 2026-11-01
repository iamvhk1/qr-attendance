# Application of Engineering Principles in Project Lifecycle

## 1. Prompt Engineering
- **Tree of Thought (ToT) Method:**
  - *Prompt:* `[design_document.tex] we need to implement the presence verification engine. Assess what all things are required and what components are needed, and how to do it, in different ways and give out the best implementation workflow.`
  - *Prompt:* `[design_document.tex] go through this and decide the next module to be implemented, and make a proper workflow plan to implement it, considering multiple approaches and choosing the best one.`
- **Chain of Thought (CoT) Method:**
  - *Prompt:* `[frontend_workflow_plan.md] go through this and implement phase 1 in the most beneficial and safe way, by creating subtasks and finishing them`
- **Iterative Refinement:**
  - Asking to improve on certain aspects of the initial result of a prompt (e.g., providing images/screenshots for further refinement).

## 2. Debugging
- **Intervening in Agent Loops:**
  - *Context:* The agent went on an endless loop trying to fix an error, failing to notice it was due to a change in persistence settings (H2 database was turned off but tests assumed it was on).
  - *Prompt:* `"for the initial part of testing, the persistence festure of the H2 databse was removed. check that"`
- **Visual Debugging:**
  - *Prompt:* Providing an image (`download.jpg`) of a faulty webpage with the context: `"the qr code isn't visible"`
- **Compilation & Stack Trace Debugging:**
  - *Prompt:* Pasting specific compilation errors to pinpoint failures: `"Type '{ children: Element[]; glass: true; accent: true; padding: "lg"; style: CSSProperties; }' is not assignable... this is in ScanPage.tsx . check it out"`
  - Consistently pasting compilation error messages which contain the location of failure to help the agent fix them.
- **Logic & Edge Case Debugging:**
  - *Prompt:* Giving unexpected workings of the inner logic while testing: `"if the session is ended early by the professor then the coverage percentage should be recalculated right?"`

## 3. Version Control (Git)
- **Pre-commit Validation:**
  - *Prompt:* `"do a global sanity check, and then push it into the repo after validating everything"`
- **Diff Analysis & Regression Tracking:**
  - *Prompt:* `"The code was working fine before the last few changes, but now the QR generation endpoint is failing. Check the current code against the last pushed commit, review the git diff, and figure out what recent change suddenly broke the code."`

## 4. Testing, Refactoring & Documentation
- **Test-Driven Development (Reverse Method):**
  - *Prompt:* Generating the test suite before implementing the code: `"write the tests for this module first. It should cover all possible edge case tests, and check everything"`
- **Exhaustive Testing & Verification:**
  - *Prompt:* `"check for correctness of the implemented code first, and then list out an exhaustive test set for this module :"`
  - *Prompt:* `"Check the working and correctness for the above phase, The codes have been implemented. Go through them and figure out the implementation correctness. Later write clear and exhaustive tests for this phase"`
  - *Prompt:* `"look throguht this plan of the last phase, and give out an extensive testing and debugging plan which clearly checks the correctness. Do not miss out on any possibility"`
  - Generating prototypes for intermediate human testing via terminal.
- **Integration/Mock Testing:**
  - *Prompt:* `"Write JUnit 5 tests for the SessionService.java method createSession(). You need to mock the CourseRepository to return a valid course, and assert that the generated QrSession has an expiration time exactly durationSeconds in the future."`
- **Refactoring for Extensibility & Clean Code:**
  - Regularly asking the agent to check for extensibility to make sure it doesn't hard code the current module into working for the current setup only.
  - *Prompt:* `"Review the SessionController.java class. Refactor the QR generation logic out of the controller and into a dedicated QrService. Ensure the controller only handles HTTP request/response formatting and delegates the business logic."`
