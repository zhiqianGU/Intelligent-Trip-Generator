risk table
Risk	                                        Probability	    Impact	    Signal	                        Mitigation
Upstream rate limits/volatility	              Medium	        High	      5xx/timeouts rise	              Precompute hot segments; circuit breaker + backoff; degrade to cache
Data sparsity (e.g.crowd)	                    Medium	        Medium	    Low confidence indices	        Generate estimated data
Sudden incidents occur during the journey	    Medium	        Medium	    Poor real-time performance	    Scope guard; prioritize core routing and re-planning
Security & privacy issues	                    Low	            High	      Scanner alerts	                JWT rotation/revocation; minimal data storage; 
