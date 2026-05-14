// import './App.css'
import { setCredentials } from "./store/authSlice";

import { Box, Button } from "@mui/material"
import { useContext, useEffect, useState } from "react"
import { AuthContext } from "react-oauth2-code-pkce"
import { useDispatch } from "react-redux";
import { BrowserRouter as Router, Navigate, Route, Routes, useLocation } from "react-router"
import ActivityList from "./components/ActivityList";
import ActivityForm from "./components/ActivityForm";
import ActivityDetail from "./components/ActivityDetail";

const ActivitiesPage = () => {
  const [refreshKey, setRefreshKey] = useState(0);
  const handleActivityAdded = () => setRefreshKey((k) => k + 1);

  return (
    <Box sx={{ p: 2, border: '1px dashed grey'}}>
      <ActivityForm onActivityAdded={ () => window.location.reload()} />
      <ActivityList key={refreshKey} />
    </Box>

    // <Box sx={{ p: 2, border: '1px dashed grey'}}>
    //   <ActivityForm onActivityAdded={handleActivityAdded} />
    //   <ActivityList key={refreshKey} />
    // </Box>
  );
}

function App() {

  const {token, tokenData, logIn, logOut, isAuthenticated}
     = useContext(AuthContext);
  const dispatch = useDispatch();
  const [authReady, setAuthReady] = useState(false);

  useEffect(() => { 
    if (token) {
      dispatch(setCredentials({ token, user: tokenData }));
      setAuthReady(true);

    }
  
  }, [token, tokenData, dispatch]);

  return (
    <Router>
      {!token ? (
      <Button variant="contained"
      onClick={() => logIn()}>
      LOGIN
    </Button>
    ) : (
      <div>
       <Box component="section" sx={{ p: 2, border: '1px dashed grey' }}>
      <Button variant="contained"
      onClick={() => logOut()}>
      LOGOUT
    </Button>
    <Routes>
      
      <Route path="/activities" element={<ActivitiesPage />} />
      <Route path="/activities/:id" element={<ActivityDetail />} />
      <Route path="/" element={token ? <Navigate to="/activities" replace /> : <div>Welcome! Please login</div>} />
    </Routes>
    </Box>
      </div>
    )}
    </Router>
  )
}

export default App
